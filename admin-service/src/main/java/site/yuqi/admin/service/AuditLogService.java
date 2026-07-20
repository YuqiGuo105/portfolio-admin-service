package site.yuqi.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.domain.AuditLog;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.repo.AuditLogRepository;

import java.util.List;
import java.util.Map;

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
}
