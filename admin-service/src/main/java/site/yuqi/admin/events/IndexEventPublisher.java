package site.yuqi.admin.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobType;

import java.time.Instant;
import java.util.UUID;

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

    @Value("${portfolio.kafka.topics.search-index}")
    private String searchTopic;

    @Value("${portfolio.kafka.topics.rag-index}")
    private String ragTopic;

    public void publish(IndexingJob job) {
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

        kafkaTemplate.send(topic, partitionKey, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish {} event for {}:{}",
                        job.getJobType(), job.getSourceType(), job.getSourceIdText(), ex);
            } else if (log.isDebugEnabled()) {
                log.debug("Published {} event to {} partition {} offset {}",
                        job.getJobType(), topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
