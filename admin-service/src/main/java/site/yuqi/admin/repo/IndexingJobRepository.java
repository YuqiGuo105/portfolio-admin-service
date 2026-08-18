package site.yuqi.admin.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobStatus;
import site.yuqi.admin.domain.JobType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

public interface IndexingJobRepository extends JpaRepository<IndexingJob, UUID> {

    Optional<IndexingJob> findByIdempotencyKey(String idempotencyKey);

    List<IndexingJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<IndexingJob> findByStatusOrderByCreatedAtDesc(JobStatus status, Pageable pageable);

    List<IndexingJob> findByJobTypeOrderByCreatedAtDesc(JobType jobType, Pageable pageable);

    List<IndexingJob> findByStatusAndJobTypeOrderByCreatedAtDesc(JobStatus status, JobType jobType, Pageable pageable);

    List<IndexingJob> findBySourceTypeAndSourceIdTextOrderByCreatedAtDesc(
            String sourceType, String sourceIdText, Pageable pageable);

    Optional<IndexingJob> findTopBySourceTypeAndSourceIdTextAndJobTypeOrderByCreatedAtDesc(
            String sourceType, String sourceIdText, JobType jobType);

    List<IndexingJob> findByStatusAndJobTypeAndNextRetryAtBeforeOrderByCreatedAtAsc(
            JobStatus status, JobType jobType, Instant now, Pageable pageable);

    List<IndexingJob> findByStatusInAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
            Set<JobStatus> statuses, Instant now, Pageable pageable);

    @Query(value = """
            with candidates as (
                select id
                from indexing_jobs
                where status in ('PENDING', 'FAILED', 'PROCESSING')
                  and next_retry_at <= :now
                order by created_at
                for update skip locked
                limit :batchSize
            ), claimed as (
                update indexing_jobs j
                set status = 'PROCESSING',
                    started_at = :now,
                    next_retry_at = :leaseUntil,
                    updated_at = :now,
                    version = version + 1
                from candidates c
                where j.id = c.id
                returning j.id
            )
            select id from claimed
            """, nativeQuery = true)
    List<UUID> claimReadyIds(
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("batchSize") int batchSize);
}
