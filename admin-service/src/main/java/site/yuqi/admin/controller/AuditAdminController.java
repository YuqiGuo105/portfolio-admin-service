package site.yuqi.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.service.AuditLogService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditAdminController {
    private final AuditLogService service;

    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String sourceId,
            @RequestParam(defaultValue = "50") int limit) {
        AuditAction parsed = action == null || action.isBlank() ? null : AuditAction.valueOf(action.toUpperCase());
        return ResponseEntity.ok(Map.of("items", service.search(actor, parsed, sourceType, sourceId, limit)));
    }

    @GetMapping("/{id}/diff")
    public ResponseEntity<Map<String, Object>> diff(@PathVariable UUID id) {
        return ResponseEntity.ok(service.diff(id));
    }
}
