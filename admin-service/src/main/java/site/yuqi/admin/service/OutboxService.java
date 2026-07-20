package site.yuqi.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.OutboxEventType;
import site.yuqi.admin.domain.OutboxStatus;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.domain.Topic;
import site.yuqi.admin.repo.ContentEventOutboxRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Writes to the transactional outbox. A separate worker (Kafka producer, etc.)
 * publishes pending rows. This service NEVER calls the broker directly.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final ContentEventOutboxRepository repository;

    public static String idempotencyKey(OutboxEventType eventType,
                                        SourceType sourceType,
                                        String sourceIdText,
                                        int version) {
        return eventType.name() + ":" + sourceType.name() + ":" + sourceIdText + ":v" + version;
    }

    @Transactional
    public ContentEventOutbox enqueuePublish(NormalizedContent content,
                                             int version,
                                             Topic topic) {
        String key = idempotencyKey(OutboxEventType.CONTENT_PUBLISHED,
                content.getSourceType(), content.getSourceId(), version);

        return repository.findByIdempotencyKey(key).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", id.toString());
            payload.put("eventType", OutboxEventType.CONTENT_PUBLISHED.name());
            payload.put("sourceType", content.getSourceType().name());
            payload.put("sourceId", content.getSourceId());
            payload.put("sourceVersion", version);
            payload.put("topic", topic.name());
            payload.put("title", content.getTitle());
            payload.put("summary", content.getSummary());
            payload.put("url", content.getUrl());
            payload.put("imageUrl", content.getImageUrl());
            payload.put("createdAt", Instant.now().toString());
            payload.put("idempotencyKey", key);
            payload.put("metadata", Map.of(
                    "category", content.getCategory() == null ? "" : content.getCategory(),
                    "tags", content.getTags() == null ? List.of() : content.getTags()));

            ContentEventOutbox row = ContentEventOutbox.builder()
                    .id(id)
                    .eventType(OutboxEventType.CONTENT_PUBLISHED)
                    .sourceType(content.getSourceType().name())
                    .sourceIdText(content.getSourceId())
                    .sourceVersion(version)
                    .topic(topic.name())
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .idempotencyKey(key)
                    .build();

            return repository.save(row);
        });
    }

    @Transactional(readOnly = true)
    public List<ContentEventOutbox> list(OutboxStatus status,
                                         String sourceType,
                                         String sourceId,
                                         int limit, int offset) {
        Pageable page = PageRequest.of(offset / Math.max(1, limit), Math.max(1, Math.min(limit, 200)));
        if (sourceType != null && sourceId != null) {
            return repository.findBySourceTypeAndSourceIdTextOrderByCreatedAtDesc(sourceType, sourceId, page);
        }
        if (status != null) {
            return repository.findByStatusOrderByCreatedAtDesc(status, page);
        }
        return repository.findAllByOrderByCreatedAtDesc(page);
    }

    // ----- Worker-ready APIs (kept here so workers can reuse) ---------------

    @Transactional(readOnly = true)
    public List<ContentEventOutbox> listPendingOutboxEvents(int batchSize) {
        return repository.findByStatusAndNextRetryAtBeforeOrderByCreatedAtAsc(
                OutboxStatus.PENDING,
                Instant.now(),
                PageRequest.of(0, Math.max(1, batchSize)));
    }

    @Transactional
    public List<ContentEventOutbox> claimReadyOutboxEvents(int batchSize, long leaseSeconds) {
        Instant now = Instant.now();
        List<ContentEventOutbox> events = repository
                .findByStatusInAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        Set.of(OutboxStatus.PENDING, OutboxStatus.FAILED, OutboxStatus.PROCESSING),
                        now,
                        PageRequest.of(0, Math.max(1, batchSize)));
        Instant leaseUntil = now.plusSeconds(Math.max(10, leaseSeconds));
        events.forEach(event -> {
            event.setStatus(OutboxStatus.PROCESSING);
            event.setNextRetryAt(leaseUntil);
        });
        return events;
    }

    @Transactional
    public Optional<ContentEventOutbox> markOutboxEventProcessing(UUID eventId, long leaseSeconds) {
        return repository.findById(eventId).map(event -> {
            if (event.getStatus() == OutboxStatus.SENT || event.getStatus() == OutboxStatus.DLQ) {
                return event;
            }
            event.setStatus(OutboxStatus.PROCESSING);
            event.setNextRetryAt(Instant.now().plusSeconds(Math.max(10, leaseSeconds)));
            return event;
        });
    }

    @Transactional
    public void markOutboxEventSent(UUID eventId) {
        repository.findById(eventId).ifPresent(e -> {
            e.setStatus(OutboxStatus.SENT);
            e.setSentAt(Instant.now());
            e.setLastError(null);
        });
    }

    @Transactional
    public void markOutboxEventFailed(UUID eventId, String error) {
        repository.findById(eventId).ifPresent(e -> {
            int retryCount = e.getRetryCount() + 1;
            e.setStatus(retryCount >= 8 ? OutboxStatus.DLQ : OutboxStatus.FAILED);
            e.setRetryCount(retryCount);
            e.setLastError(error);
            e.setNextRetryAt(Instant.now().plusSeconds(60L * (1L << Math.min(retryCount, 6))));
        });
    }
}
