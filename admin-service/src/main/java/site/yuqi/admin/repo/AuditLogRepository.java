package site.yuqi.admin.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import site.yuqi.admin.domain.AuditLog;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findBySourceTypeAndSourceIdTextOrderByCreatedAtDesc(
            String sourceType, String sourceIdText, Pageable pageable);
}
