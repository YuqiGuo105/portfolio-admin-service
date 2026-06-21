package site.yuqi.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.domain.OutboxStatus;
import site.yuqi.admin.service.OutboxService;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/outbox-events")
@RequiredArgsConstructor
@Tag(name = "Admin Outbox", description = "Inspect content_event_outbox rows for debugging.")
public class OutboxAdminController {

    private final OutboxService outboxService;

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
}
