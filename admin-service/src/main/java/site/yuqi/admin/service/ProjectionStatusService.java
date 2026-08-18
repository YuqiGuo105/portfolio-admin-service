package site.yuqi.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobStatus;
import site.yuqi.admin.domain.JobType;
import site.yuqi.admin.domain.OutboxStatus;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.dto.ProjectionStatusResponse;
import site.yuqi.admin.repo.ContentEventOutboxRepository;
import site.yuqi.admin.repo.IndexingJobRepository;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProjectionStatusService {

    private final IndexingJobRepository jobs;
    private final ContentEventOutboxRepository outbox;

    @Transactional(readOnly = true)
    public ProjectionStatusResponse status(SourceType sourceType, String sourceId) {
        Optional<IndexingJob> search = jobs.findTopBySourceTypeAndSourceIdTextAndJobTypeOrderByCreatedAtDesc(
                sourceType.name(), sourceId, JobType.SEARCH_INDEX);
        Optional<IndexingJob> rag = jobs.findTopBySourceTypeAndSourceIdTextAndJobTypeOrderByCreatedAtDesc(
                sourceType.name(), sourceId, JobType.RAG_INDEX);
        Optional<ContentEventOutbox> notification = outbox
                .findTopBySourceTypeAndSourceIdTextOrderByCreatedAtDesc(sourceType.name(), sourceId);
        int version = Math.max(
                Math.max(search.map(IndexingJob::getSourceVersion).orElse(0),
                        rag.map(IndexingJob::getSourceVersion).orElse(0)),
                notification.map(ContentEventOutbox::getSourceVersion).orElse(0));
        return new ProjectionStatusResponse(
                sourceType.name(),
                sourceId,
                version,
                overall(version, search, rag, notification),
                search.map(this::projection).orElseGet(this::missing),
                rag.map(this::projection).orElseGet(this::missing),
                notification.map(this::projection).orElseGet(this::missing));
    }

    private String overall(int expectedVersion, Optional<IndexingJob> search, Optional<IndexingJob> rag,
                           Optional<ContentEventOutbox> notification) {
        if (search.isEmpty() || rag.isEmpty() || notification.isEmpty()) return "INCOMPLETE";
        if (search.get().getSourceVersion() != expectedVersion
                || rag.get().getSourceVersion() != expectedVersion
                || notification.get().getSourceVersion() != expectedVersion) return "PROCESSING";
        if (search.get().getStatus() == JobStatus.DONE
                && rag.get().getStatus() == JobStatus.DONE
                && notification.get().getStatus() == OutboxStatus.SENT) return "READY";
        if (search.get().getStatus() == JobStatus.FAILED
                || rag.get().getStatus() == JobStatus.FAILED
                || notification.get().getStatus() == OutboxStatus.FAILED
                || notification.get().getStatus() == OutboxStatus.DLQ) return "DEGRADED";
        return "PROCESSING";
    }

    private ProjectionStatusResponse.Projection projection(IndexingJob job) {
        return new ProjectionStatusResponse.Projection(
                job.getStatus().name(), job.getRetryCount(), job.getUpdatedAt(), job.getLastError());
    }

    private ProjectionStatusResponse.Projection projection(ContentEventOutbox event) {
        return new ProjectionStatusResponse.Projection(
                event.getStatus().name(), event.getRetryCount(), event.getUpdatedAt(), event.getLastError());
    }

    private ProjectionStatusResponse.Projection missing() {
        return new ProjectionStatusResponse.Projection("MISSING", 0, null, null);
    }
}
