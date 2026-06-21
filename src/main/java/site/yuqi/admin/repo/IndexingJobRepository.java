package site.yuqi.admin.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobStatus;
import site.yuqi.admin.domain.JobType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
}
