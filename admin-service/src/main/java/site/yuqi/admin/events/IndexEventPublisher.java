package site.yuqi.admin.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobType;
import site.yuqi.admin.operations.OperationContext;
import site.yuqi.admin.operations.OperationEventPublisher;
import site.yuqi.admin.service.IndexingJobService;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes {@link ContentIndexEvent}s to the Kafka topics consumed by
 * {@code portfolio-search-indexer} and {@code portfolio-rag-indexer}.
 *
 * <p>The {@code indexing_jobs} row is created by {@code IndexingJobService}
 * in the same transaction as the publish workflow. This component fires the
 * Kafka event AFTER the transaction commits (via a callback in
 * {@code ContentService}), so consumers never see an event for a job that
 * does not exist in the DB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexEventPublisher {

    private final KafkaTemplate<String, ContentIndexEvent> kafkaTemplate;
    private final IndexingJobService jobs;
    private final IndexerWakeClient wakeClient;
    private final OperationEventPublisher operations;

    @Value("${portfolio.kafka.topics.search-index}")
    private String searchTopic;

    @Value("${portfolio.kafka.topics.rag-index}")
    private String ragTopic;

    public CompletableFuture<?> publish(IndexingJob job) {
        OperationContext context = OperationContext.current();
        jobs.markIndexingJobDispatching(job.getId(), 300);
        ContentIndexEvent event = ContentIndexEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .traceId(context.traceId())
                .correlationId(context.correlationId())
                .causationId(job.getId().toString())
                .schemaVersion(1)
                .sourceType(job.getSourceType())
                .sourceId(job.getSourceIdText())
                .sourceVersion(job.getSourceVersion())
                .indexingJobId(job.getId() == null ? null : job.getId().toString())
                .idempotencyKey(job.getIdempotencyKey())
                .jobType(job.getJobType().name())
                .build();

        String topic = job.getJobType() == JobType.SEARCH_INDEX ? searchTopic : ragTopic;
        String partitionKey = job.getSourceType() + ":" + job.getSourceIdText();

        operations.publish("content." + job.getJobType().name().toLowerCase() + ".dispatched",
                "RUNNING", job.getSourceType(), job.getSourceIdText(), job.getSourceVersion(),
                job.getId().toString(), job.getIdempotencyKey(),
                java.util.Map.of("indexingJobId", job.getId().toString(), "topic", topic));

        return kafkaTemplate.send(topic, partitionKey, event).whenCompleteAsync((result, ex) -> {
            if (ex != null) {
                jobs.markIndexingJobFailed(job.getId(), ex.getMessage());
                operations.publishWithContext(event.getTraceId(), event.getCorrelationId(), "system",
                        "content." + job.getJobType().name().toLowerCase() + ".dispatch_failed",
                        "FAILED", job.getSourceType(), job.getSourceIdText(), job.getSourceVersion(),
                        event.getEventId(), job.getIdempotencyKey(),
                        java.util.Map.of("errorType", ex.getClass().getSimpleName()));
                log.error("Failed to publish {} event for {}:{}",
                        job.getJobType(), job.getSourceType(), job.getSourceIdText(), ex);
                return;
            }
            log.info("Published {} event to {} partition {} offset {}",
                    job.getJobType(), topic,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            operations.publishWithContext(event.getTraceId(), event.getCorrelationId(), "system",
                    "content." + job.getJobType().name().toLowerCase() + ".published",
                    "SUCCEEDED", job.getSourceType(), job.getSourceIdText(), job.getSourceVersion(),
                    event.getEventId(), job.getIdempotencyKey(),
                    java.util.Map.of("topic", topic, "partition", result.getRecordMetadata().partition(),
                            "offset", result.getRecordMetadata().offset()));
            if (!wakeClient.wakeAndAwait(job)) {
                jobs.markIndexingJobFailed(job.getId(), "Indexer did not complete within wake lease");
            }
        });
    }
}
