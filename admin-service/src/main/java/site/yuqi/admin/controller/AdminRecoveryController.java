package site.yuqi.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobStatus;
import site.yuqi.admin.domain.OutboxStatus;
import site.yuqi.admin.events.IndexEventPublisher;
import site.yuqi.admin.events.IndexingJobRelay;
import site.yuqi.admin.events.NotificationEventPublisher;
import site.yuqi.admin.events.NotificationOutboxRelay;
import site.yuqi.admin.security.AdminPrincipal;
import site.yuqi.admin.service.AuditLogService;
import site.yuqi.admin.service.IndexingJobService;
import site.yuqi.admin.service.OutboxService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/recovery")
@RequiredArgsConstructor
@Tag(name = "Admin Recovery", description = "Inspect, retry and replay failed async workflow steps.")
public class AdminRecoveryController {

    private final IndexingJobService indexingJobService;
    private final OutboxService outboxService;
    private final AuditLogService auditLogService;
    private final IndexEventPublisher indexEventPublisher;
    private final NotificationEventPublisher notificationEventPublisher;
    private final ObjectProvider<IndexingJobRelay> indexingRelay;
    private final ObjectProvider<NotificationOutboxRelay> notificationRelay;
    private final site.yuqi.admin.operations.OperationEventJournal journal;

    @GetMapping("/failures")
    @Operation(summary = "List failed, skipped or dead-lettered async workflow steps")
    public ResponseEntity<Map<String, Object>> failures(
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        String normalizedKind = normalizeKind(kind);
        List<Map<String, Object>> items = new ArrayList<>();

        if (normalizedKind == null || "INDEXING_JOB".equals(normalizedKind)) {
            items.addAll(indexingJobService.list(JobStatus.DLQ, null, null, null, boundedLimit, 0)
                    .stream().map(this::indexingItem).toList());
            items.addAll(indexingJobService.list(JobStatus.FAILED, null, null, null, boundedLimit, 0)
                    .stream().map(this::indexingItem).toList());
            items.addAll(indexingJobService.list(JobStatus.SKIPPED, null, null, null, boundedLimit, 0)
                    .stream().map(this::indexingItem).toList());
        }
        if (normalizedKind == null || "OUTBOX_EVENT".equals(normalizedKind)) {
            items.addAll(outboxService.list(OutboxStatus.FAILED, null, null, boundedLimit, 0)
                    .stream().map(this::outboxItem).toList());
            items.addAll(outboxService.list(OutboxStatus.DLQ, null, null, boundedLimit, 0)
                    .stream().map(this::outboxItem).toList());
        }

        items.sort(Comparator.comparing(
                item -> (Instant) item.getOrDefault("updatedAt", Instant.EPOCH),
                Comparator.reverseOrder()));
        if (items.size() > boundedLimit) {
            items = items.subList(0, boundedLimit);
        }
        return ResponseEntity.ok(Map.of("items", items, "limit", boundedLimit));
    }

    @PostMapping("/failures/{kind}/{id}/retry")
    @Operation(summary = "Retry one failed async workflow step")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable String kind,
                                                     @PathVariable UUID id,
                                                     HttpServletRequest request) {
        String normalizedKind = normalizeKind(kind);
        if (normalizedKind == null) {
            throw new IllegalArgumentException("Unsupported recovery kind: " + kind);
        }
        String actor = AdminPrincipal.from(request);
        return switch (normalizedKind) {
            case "INDEXING_JOB" -> {
                IndexingJob job = indexingJobService.retry(id);
                auditLogService.log(actor, AuditAction.RETRY_INDEXING_JOB,
                        job.getSourceType(), job.getSourceIdText(), job.getSourceVersion(),
                        null, Map.of("jobId", id.toString(), "jobType", job.getJobType().name()));
                indexEventPublisher.publish(job);
                yield ResponseEntity.ok(Map.of("kind", "INDEXING_JOB", "item", job, "status", "queued"));
            }
            case "OUTBOX_EVENT" -> {
                ContentEventOutbox event = outboxService.retry(id);
                auditLogService.log(actor, AuditAction.RETRY_OUTBOX_EVENT,
                        event.getSourceType(), event.getSourceIdText(), event.getSourceVersion(),
                        null, Map.of("eventId", id.toString(), "topic", event.getTopic()));
                notificationEventPublisher.publish(event);
                yield ResponseEntity.ok(Map.of("kind", "OUTBOX_EVENT", "item", event, "status", "queued"));
            }
            default -> throw new IllegalArgumentException("Unsupported recovery kind: " + kind);
        };
    }

    @PostMapping("/drain")
    @Operation(summary = "Drain ready outbox and indexing workers once")
    public ResponseEntity<Map<String, Object>> drain() {
        NotificationOutboxRelay notificationWorker = notificationRelay.getIfAvailable();
        IndexingJobRelay indexingWorker = indexingRelay.getIfAvailable();
        int notifications = notificationWorker == null ? 0 : notificationWorker.drainOnce();
        int indexing = indexingWorker == null ? 0 : indexingWorker.drainOnce();
        return ResponseEntity.ok(Map.of("status", "accepted", "notificationEvents", notifications, "indexingJobs", indexing,"auditEvents",journal.drain()));
    }

    private Map<String, Object> indexingItem(IndexingJob job) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "INDEXING_JOB");
        item.put("id", job.getId());
        item.put("status", job.getStatus());
        item.put("operation", job.getJobType());
        item.put("sourceType", job.getSourceType());
        item.put("sourceId", job.getSourceIdText());
        item.put("version", job.getSourceVersion());
        item.put("retryCount", job.getRetryCount());
        item.put("lastError", safe(job.getLastError()));
        item.put("updatedAt", job.getUpdatedAt() == null ? Instant.EPOCH : job.getUpdatedAt());
        return item;
    }

    private Map<String, Object> outboxItem(ContentEventOutbox event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "OUTBOX_EVENT");
        item.put("id", event.getId());
        item.put("status", event.getStatus());
        item.put("operation", event.getEventType());
        item.put("topic", event.getTopic());
        item.put("sourceType", event.getSourceType());
        item.put("sourceId", event.getSourceIdText());
        item.put("version", event.getSourceVersion());
        item.put("retryCount", event.getRetryCount());
        item.put("lastError", safe(event.getLastError()));
        item.put("updatedAt", event.getUpdatedAt() == null ? Instant.EPOCH : event.getUpdatedAt());
        return item;
    }

    private static String normalizeKind(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
