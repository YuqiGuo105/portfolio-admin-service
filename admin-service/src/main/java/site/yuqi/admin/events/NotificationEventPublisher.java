package site.yuqi.admin.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.OutboxEventType;
import site.yuqi.admin.domain.Topic;
import site.yuqi.admin.service.OutboxService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    private final OutboxService outboxService;
    private final NotificationDeliveryClient deliveryClient;

    @Value("${portfolio.kafka.topics.notification.article-updates}")
    private String articleUpdatesTopic;

    @Value("${portfolio.kafka.topics.notification.feature-updates}")
    private String featureUpdatesTopic;

    @Value("${portfolio.kafka.topics.notification.job-updates}")
    private String jobUpdatesTopic;

    public CompletableFuture<?> publish(ContentEventOutbox outbox, NormalizedContent content,
                        int version, Topic notificationTopic) {
        outboxService.markOutboxEventProcessing(outbox.getId(), 60);
        String idempotencyKey = OutboxService.idempotencyKey(
                OutboxEventType.CONTENT_PUBLISHED,
                content.getSourceType(),
                content.getSourceId(),
                version);

        ContentPublishedEvent event = ContentPublishedEvent.builder()
                .eventId(outbox.getId().toString())
                .occurredAt(outbox.getCreatedAt() == null ? Instant.now() : outbox.getCreatedAt())
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

        return send(outbox, event, notificationTopic,
                content.getSourceType().name() + ":" + content.getSourceId());
    }

    public CompletableFuture<?> publish(ContentEventOutbox outbox) {
        Map<String, Object> payload = outbox.getPayload();
        Map<String, Object> metadata = asMap(payload.get("metadata"));
        Topic notificationTopic = Topic.valueOf(outbox.getTopic());
        String sourceType = outbox.getSourceType();
        String sourceId = outbox.getSourceIdText();

        ContentPublishedEvent event = ContentPublishedEvent.builder()
                .eventId(outbox.getId().toString())
                .occurredAt(outbox.getCreatedAt() == null ? Instant.now() : outbox.getCreatedAt())
                .sourceType(sourceType)
                .sourceId(sourceId)
                .sourceVersion(outbox.getSourceVersion())
                .notificationTopic(notificationTopic.name())
                .title(asString(payload.get("title")))
                .summary(asString(payload.get("summary")))
                .url(asString(payload.get("url")))
                .imageUrl(asString(payload.get("imageUrl")))
                .category(asString(metadata.get("category")))
                .tags(asStringList(metadata.get("tags")))
                .idempotencyKey(outbox.getIdempotencyKey())
                .build();

        return send(outbox, event, notificationTopic, sourceType + ":" + sourceId);
    }

    private CompletableFuture<?> send(ContentEventOutbox outbox, ContentPublishedEvent event,
                      Topic notificationTopic, String partitionKey) {

        String kafkaTopic = resolveKafkaTopic(notificationTopic);

        return kafkaTemplate.send(kafkaTopic, partitionKey, event).whenCompleteAsync((result, ex) -> {
            if (ex != null) {
                outboxService.markOutboxEventFailed(outbox.getId(), ex.getMessage());
                log.error("Failed to publish notification outbox event {} topic={}",
                        outbox.getId(), kafkaTopic, ex);
            } else if (deliveryClient.deliver(event)) {
                outboxService.markOutboxEventSent(outbox.getId());
                log.info("Published notification event for {}:{} v{} → topic={} partition={} offset={}",
                        outbox.getSourceType(), outbox.getSourceIdText(), outbox.getSourceVersion(),
                        kafkaTopic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                outboxService.markOutboxEventFailed(outbox.getId(),
                        "Notification worker wake/delivery did not complete");
            }
        });
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private String resolveKafkaTopic(Topic notificationTopic) {
        return switch (notificationTopic) {
            case ARTICLE_UPDATES  -> articleUpdatesTopic;
            case FEATURE_UPDATES  -> featureUpdatesTopic;
            case JOB_UPDATES      -> jobUpdatesTopic;
        };
    }
}
