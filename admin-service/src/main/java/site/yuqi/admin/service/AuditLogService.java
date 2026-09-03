package site.yuqi.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.domain.AuditLog;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.repo.AuditLogRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository repository;

    @Transactional
    public AuditLog log(String actor, AuditAction action, SourceType sourceType, String sourceId,
                        Integer version, Map<String, Object> before, Map<String, Object> after) {
        return log(actor, action, sourceType.name(), sourceId, version, before, after);
    }

    @Transactional
    public AuditLog log(String actor, AuditAction action, String sourceType, String sourceId,
                        Integer version, Map<String, Object> before, Map<String, Object> after) {
        return repository.save(AuditLog.builder()
                .actor(actor)
                .action(action)
                .sourceType(sourceType)
                .sourceIdText(sourceId)
                .sourceVersion(version)
                .beforeSnapshot(before)
                .afterSnapshot(after)
                .build());
    }

    @Transactional(readOnly = true)
    public List<AuditLog> recentFor(SourceType sourceType, String sourceId, int limit) {
        return repository.findBySourceTypeAndSourceIdTextOrderByCreatedAtDesc(
                sourceType.name(), sourceId,
                PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    @Transactional(readOnly = true)
    public List<AuditLog> search(String actor, AuditAction action, String sourceType,
                                 String sourceId, int limit) {
        Specification<AuditLog> spec = Specification.where(null);
        if (actor != null && !actor.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("actor")),
                    "%" + actor.toLowerCase() + "%"));
        }
        if (action != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("action"), action));
        if (sourceType != null && !sourceType.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("sourceType"), sourceType.toUpperCase()));
        }
        if (sourceId != null && !sourceId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("sourceIdText"), sourceId));
        }
        return repository.findAll(spec, PageRequest.of(0, Math.max(1, Math.min(limit, 200)),
                Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> diff(UUID id) {
        AuditLog log = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Audit entry not found: " + id));
        Map<String, Object> before = log.getBeforeSnapshot() == null ? Map.of() : log.getBeforeSnapshot();
        Map<String, Object> after = log.getAfterSnapshot() == null ? Map.of() : log.getAfterSnapshot();
        Map<String, Object> changes = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            Object oldValue = before.get(key);
            Object newValue = after.get(key);
            if (!java.util.Objects.deepEquals(oldValue, newValue)) {
                changes.put(key, Map.of("before", oldValue == null ? "" : oldValue,
                        "after", newValue == null ? "" : newValue));
            }
        }
        return Map.of("auditId", id, "actor", log.getActor(), "action", log.getAction(),
                "sourceType", log.getSourceType(), "sourceId", log.getSourceIdText(),
                "sourceVersion", log.getSourceVersion() == null ? 0 : log.getSourceVersion(),
                "changedFieldCount", changes.size(), "changes", changes);
    }
}
