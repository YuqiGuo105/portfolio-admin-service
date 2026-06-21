package site.yuqi.admin.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.OutboxStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentEventOutboxRepository extends JpaRepository<ContentEventOutbox, UUID> {

    Optional<ContentEventOutbox> findByIdempotencyKey(String idempotencyKey);

    List<ContentEventOutbox> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<ContentEventOutbox> findByStatusOrderByCreatedAtDesc(OutboxStatus status, Pageable pageable);

    List<ContentEventOutbox> findBySourceTypeAndSourceIdTextOrderByCreatedAtDesc(
            String sourceType, String sourceIdText, Pageable pageable);

    List<ContentEventOutbox> findByStatusAndNextRetryAtBeforeOrderByCreatedAtAsc(
            OutboxStatus status, Instant now, Pageable pageable);
}
