package site.yuqi.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobStatus;
import site.yuqi.admin.domain.JobType;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.repo.IndexingJobRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IndexingJobService {

    private final IndexingJobRepository repository;

    @Transactional
    public IndexingJob enqueueKnowledge(String sourceId, int version) {
        String key = "RAG_INDEX:OWNER_QA:" + sourceId + ":v" + version;
        return repository.findByIdempotencyKey(key).orElseGet(() -> repository.save(IndexingJob.builder()
                .jobType(JobType.RAG_INDEX).sourceType("OWNER_QA").sourceIdText(sourceId)
                .sourceVersion(version).status(JobStatus.PENDING).idempotencyKey(key).build()));
    }

    public static String publishKey(JobType jobType, SourceType sourceType, String sourceId, int version) {
        return jobType.name() + ":" + sourceType.name() + ":" + sourceId + ":v" + version;
    }

    public static String manualKey(JobType jobType, SourceType sourceType, String sourceId, int version) {
        return jobType.name() + ":" + sourceType.name() + ":" + sourceId + ":v" + version
                + ":manual:" + Instant.now().toEpochMilli();
    }

    @Transactional
    public IndexingJob enqueue(JobType jobType, SourceType sourceType, String sourceId,
                               int version, String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey).orElseGet(() ->
            repository.save(IndexingJob.builder()
                    .jobType(jobType)
                    .sourceType(sourceType.name())
                    .sourceIdText(sourceId)
                    .sourceVersion(version)
                    .status(JobStatus.PENDING)
                    .idempotencyKey(idempotencyKey)
                    .build())
        );
    }

    @Transactional(readOnly = true)
    public List<IndexingJob> list(JobStatus status, JobType jobType,
                                  String sourceType, String sourceId,
                                  int limit, int offset) {
        Pageable page = PageRequest.of(offset / Math.max(1, limit), Math.max(1, Math.min(limit, 200)));
        if (sourceType != null && sourceId != null) {
            return repository.findBySourceTypeAndSourceIdTextOrderByCreatedAtDesc(sourceType, sourceId, page);
        }
        if (status != null && jobType != null) {
            return repository.findByStatusAndJobTypeOrderByCreatedAtDesc(status, jobType, page);
        }
        if (status != null) {
            return repository.findByStatusOrderByCreatedAtDesc(status, page);
        }
        if (jobType != null) {
            return repository.findByJobTypeOrderByCreatedAtDesc(jobType, page);
        }
        return repository.findAllByOrderByCreatedAtDesc(page);
    }

    @Transactional(readOnly = true)
    public Optional<IndexingJob> latestJob(SourceType sourceType, String sourceId, JobType jobType) {
        return repository.findTopBySourceTypeAndSourceIdTextAndJobTypeOrderByCreatedAtDesc(
                sourceType.name(), sourceId, jobType);
    }

    @Transactional
    public IndexingJob retry(UUID jobId) {
        IndexingJob job = repository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Indexing job not found: " + jobId));
        if (job.getStatus() != JobStatus.FAILED && job.getStatus() != JobStatus.SKIPPED && job.getStatus() != JobStatus.DLQ) {
            throw new IllegalStateException("Only FAILED or SKIPPED jobs can be retried (was " + job.getStatus() + ")");
        }
        job.setStatus(JobStatus.PENDING);
        job.setNextRetryAt(Instant.now());
        job.setLastError(null);
        job.setRetryCount(0);
        return job;
    }

    // ----- Worker-ready APIs (kept here so workers can reuse) ---------------

    @Transactional(readOnly = true)
    public List<IndexingJob> listPendingIndexingJobs(JobType jobType, int batchSize) {
        return repository.findByStatusAndJobTypeAndNextRetryAtBeforeOrderByCreatedAtAsc(
                JobStatus.PENDING, jobType, Instant.now(),
                PageRequest.of(0, Math.max(1, batchSize)));
    }

    @Transactional
    public List<IndexingJob> claimReadyIndexingJobs(int batchSize, long leaseSeconds) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plusSeconds(Math.max(30, leaseSeconds));
        List<UUID> claimedIds = repository.claimReadyIds(now, leaseUntil, Math.max(1, batchSize));
        return repository.findAllById(claimedIds);
    }

    @Transactional
    public void markIndexingJobProcessing(UUID jobId) {
        repository.findById(jobId).ifPresent(j -> {
            j.setStatus(JobStatus.PROCESSING);
            j.setStartedAt(Instant.now());
        });
    }

    @Transactional
    public void markIndexingJobDispatching(UUID jobId, long leaseSeconds) {
        repository.findById(jobId).ifPresent(j -> {
            if (j.getStatus() == JobStatus.DONE) return;
            j.setStatus(JobStatus.PROCESSING);
            j.setStartedAt(Instant.now());
            j.setNextRetryAt(Instant.now().plusSeconds(Math.max(30, leaseSeconds)));
        });
    }

    @Transactional
    public void markIndexingJobDone(UUID jobId) {
        repository.findById(jobId).ifPresent(j -> {
            if (j.getStatus() != JobStatus.PROCESSING) return;
            j.setStatus(JobStatus.DONE);
            j.setCompletedAt(Instant.now());
            j.setLastError(null);
        });
    }

    @Transactional
    public void markIndexingJobFailed(UUID jobId, String error) {
        repository.findById(jobId).ifPresent(j -> {
            if (j.getStatus() != JobStatus.PROCESSING) return;
            j.setStatus(j.getRetryCount() + 1 >= 8 ? JobStatus.DLQ : JobStatus.FAILED);
            j.setRetryCount(j.getRetryCount() + 1);
            j.setLastError(error);
            j.setNextRetryAt(Instant.now().plusSeconds(60L * (1L << Math.min(j.getRetryCount(), 6))));
        });
    }
}
