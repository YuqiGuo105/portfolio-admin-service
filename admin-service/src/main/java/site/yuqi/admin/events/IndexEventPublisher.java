package site.yuqi.admin.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobType;
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

    @Value("${portfolio.kafka.topics.search-index}")
    private String searchTopic;

    @Value("${portfolio.kafka.topics.rag-index}")
    private String ragTopic;

    public CompletableFuture<?> publish(IndexingJob job) {
        jobs.markIndexingJobDispatching(job.getId(), 300);
        ContentIndexEvent event = ContentIndexEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .sourceType(job.getSourceType())
                .sourceId(job.getSourceIdText())
                .sourceVersion(job.getSourceVersion())
                .indexingJobId(job.getId() == null ? null : job.getId().toString())
                .idempotencyKey(job.getIdempotencyKey())
                .jobType(job.getJobType().name())
                .build();

        String topic = job.getJobType() == JobType.SEARCH_INDEX ? searchTopic : ragTopic;
        String partitionKey = job.getSourceType() + ":" + job.getSourceIdText();

        return kafkaTemplate.send(topic, partitionKey, event).whenCompleteAsync((result, ex) -> {
            if (ex != null) {
                jobs.markIndexingJobFailed(job.getId(), ex.getMessage());
                log.error("Failed to publish {} event for {}:{}",
                        job.getJobType(), job.getSourceType(), job.getSourceIdText(), ex);
                return;
            }
            log.info("Published {} event to {} partition {} offset {}",
                    job.getJobType(), topic,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            if (!wakeClient.wakeAndAwait(job)) {
                jobs.markIndexingJobFailed(job.getId(), "Indexer did not complete within wake lease");
            }
        });
    }
}
