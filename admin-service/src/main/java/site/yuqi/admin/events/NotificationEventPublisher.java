package site.yuqi.admin.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.OutboxEventType;
import site.yuqi.admin.domain.Topic;
import site.yuqi.admin.service.OutboxService;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@link ContentPublishedEvent}s to the notification-service
 * Kafka topics immediately after a DB transaction commits.
 *
 * <p>Topic routing mirrors the notification domain:
 * <pre>
 *   ARTICLE_UPDATES  → content.notification.article-updates.v1
 *   FEATURE_UPDATES  → content.notification.feature-updates.v1
 *   JOB_UPDATES      → content.notification.job-updates.v1
 * </pre>
 *
 * <p>Partition key = {@code "<sourceType>:<sourceId>"} so events for the same
 * content are always ordered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventPublisher {

    private final KafkaTemplate<String, ContentPublishedEvent> kafkaTemplate;

    @Value("${portfolio.kafka.topics.notification.article-updates}")
    private String articleUpdatesTopic;

    @Value("${portfolio.kafka.topics.notification.feature-updates}")
    private String featureUpdatesTopic;

    @Value("${portfolio.kafka.topics.notification.job-updates}")
    private String jobUpdatesTopic;

    public void publish(NormalizedContent content, int version, Topic notificationTopic) {
        String idempotencyKey = OutboxService.idempotencyKey(
                OutboxEventType.CONTENT_PUBLISHED,
                content.getSourceType(),
                content.getSourceId(),
                version);

        ContentPublishedEvent event = ContentPublishedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .sourceType(content.getSourceType().name())
                .sourceId(content.getSourceId())
                .sourceVersion(version)
                .notificationTopic(notificationTopic.name())
                .title(content.getTitle())
                .summary(content.getSummary())
                .url(content.getUrl())
                .imageUrl(content.getImageUrl())
                .category(content.getCategory())
                .tags(content.getTags())
                .idempotencyKey(idempotencyKey)
                .build();

        String kafkaTopic = resolveKafkaTopic(notificationTopic);
        String partitionKey = content.getSourceType().name() + ":" + content.getSourceId();

        kafkaTemplate.send(kafkaTopic, partitionKey, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish notification event for {}:{} topic={}",
                        content.getSourceType(), content.getSourceId(), kafkaTopic, ex);
            } else {
                log.info("Published notification event for {}:{} v{} → topic={} partition={} offset={}",
                        content.getSourceType(), content.getSourceId(), version,
                        kafkaTopic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    private String resolveKafkaTopic(Topic notificationTopic) {
        return switch (notificationTopic) {
            case ARTICLE_UPDATES  -> articleUpdatesTopic;
            case FEATURE_UPDATES  -> featureUpdatesTopic;
            case JOB_UPDATES      -> jobUpdatesTopic;
        };
    }
}
