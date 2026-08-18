package site.yuqi.admin.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.OutboxStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ContentEventOutboxRepository extends JpaRepository<ContentEventOutbox, UUID> {

    Optional<ContentEventOutbox> findByIdempotencyKey(String idempotencyKey);

    List<ContentEventOutbox> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<ContentEventOutbox> findByStatusOrderByCreatedAtDesc(OutboxStatus status, Pageable pageable);

    List<ContentEventOutbox> findBySourceTypeAndSourceIdTextOrderByCreatedAtDesc(
            String sourceType, String sourceIdText, Pageable pageable);

    List<ContentEventOutbox> findByStatusAndNextRetryAtBeforeOrderByCreatedAtAsc(
            OutboxStatus status, Instant now, Pageable pageable);

    List<ContentEventOutbox> findByStatusInAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
            Set<OutboxStatus> statuses, Instant now, Pageable pageable);

    Optional<ContentEventOutbox> findTopBySourceTypeAndSourceIdTextOrderByCreatedAtDesc(
            String sourceType, String sourceIdText);

    @Query(value = """
            with candidates as (
                select id
                from content_event_outbox
                where status in ('PENDING', 'FAILED', 'PROCESSING')
                  and next_retry_at <= :now
                order by created_at
                for update skip locked
                limit :batchSize
            ), claimed as (
                update content_event_outbox o
                set status = 'PROCESSING',
                    next_retry_at = :leaseUntil,
                    updated_at = :now,
                    version = version + 1
                from candidates c
                where o.id = c.id
                returning o.id
            )
            select id from claimed
            """, nativeQuery = true)
    List<UUID> claimReadyIds(
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("batchSize") int batchSize);
}
