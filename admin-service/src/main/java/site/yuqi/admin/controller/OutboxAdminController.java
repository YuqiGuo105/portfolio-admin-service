package site.yuqi.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.OutboxStatus;
import site.yuqi.admin.events.NotificationEventPublisher;
import site.yuqi.admin.security.AdminPrincipal;
import site.yuqi.admin.service.AuditLogService;
import site.yuqi.admin.service.OutboxService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/outbox-events")
@RequiredArgsConstructor
@Tag(name = "Admin Outbox", description = "Inspect content_event_outbox rows for debugging.")
public class OutboxAdminController {

    private final OutboxService outboxService;
    private final AuditLogService auditLogService;
    private final NotificationEventPublisher notificationEventPublisher;

    @GetMapping
    @Operation(summary = "List outbox events")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "status", required = false) String statusRaw,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "sourceId", required = false) String sourceId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {

        OutboxStatus status = statusRaw == null ? null : OutboxStatus.valueOf(statusRaw.toUpperCase());

        return ResponseEntity.ok(Map.of("items",
                outboxService.list(status, sourceType, sourceId, limit, offset)));
    }

    @PostMapping("/{eventId}/retry")
    @Operation(summary = "Retry a failed outbox event")
    public ResponseEntity<ContentEventOutbox> retry(@PathVariable UUID eventId,
                                                    HttpServletRequest request) {
        ContentEventOutbox event = outboxService.retry(eventId);
        auditLogService.log(AdminPrincipal.from(request), AuditAction.RETRY_OUTBOX_EVENT,
                event.getSourceType(), event.getSourceIdText(), event.getSourceVersion(),
                null, Map.of("eventId", eventId.toString(), "topic", event.getTopic()));
        notificationEventPublisher.publish(event);
        return ResponseEntity.ok(event);
    }

    @PostMapping("/{eventId}/replay")
    @Operation(summary = "Replay a sent or dead-lettered outbox event")
    public ResponseEntity<ContentEventOutbox> replay(@PathVariable UUID eventId,
                                                     HttpServletRequest request) {
        ContentEventOutbox event = outboxService.replay(eventId);
        auditLogService.log(AdminPrincipal.from(request), AuditAction.REPLAY_OUTBOX_EVENT,
                event.getSourceType(), event.getSourceIdText(), event.getSourceVersion(),
                null, Map.of("eventId", eventId.toString(), "topic", event.getTopic()));
        notificationEventPublisher.publish(event);
        return ResponseEntity.ok(event);
    }
}
