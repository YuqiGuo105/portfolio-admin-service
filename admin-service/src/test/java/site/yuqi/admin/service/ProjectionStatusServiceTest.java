package site.yuqi.admin.service;

import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectionStatusServiceTest {

    private final IndexingJobRepository jobs = mock(IndexingJobRepository.class);
    private final ContentEventOutboxRepository outbox = mock(ContentEventOutboxRepository.class);
    private final ProjectionStatusService service = new ProjectionStatusService(jobs, outbox);

    @Test
    void reportsReadyOnlyWhenEveryProjectionReachedTheSameSourceVersion() {
        stub(job(JobType.SEARCH_INDEX, JobStatus.DONE, 3),
                job(JobType.RAG_INDEX, JobStatus.DONE, 3), event(OutboxStatus.SENT, 3));

        ProjectionStatusResponse result = service.status(SourceType.BLOG, "article-1");

        assertThat(result.overallStatus()).isEqualTo("READY");
        assertThat(result.sourceVersion()).isEqualTo(3);
    }

    @Test
    void reportsProcessingWhenOneProjectionStillRepresentsAnOlderVersion() {
        stub(job(JobType.SEARCH_INDEX, JobStatus.DONE, 3),
                job(JobType.RAG_INDEX, JobStatus.DONE, 2), event(OutboxStatus.SENT, 3));

        ProjectionStatusResponse result = service.status(SourceType.BLOG, "article-1");

        assertThat(result.overallStatus()).isEqualTo("PROCESSING");
    }

    private void stub(IndexingJob search, IndexingJob rag, ContentEventOutbox notification) {
        when(jobs.findTopBySourceTypeAndSourceIdTextAndJobTypeOrderByCreatedAtDesc(
                "BLOG", "article-1", JobType.SEARCH_INDEX)).thenReturn(Optional.of(search));
        when(jobs.findTopBySourceTypeAndSourceIdTextAndJobTypeOrderByCreatedAtDesc(
                "BLOG", "article-1", JobType.RAG_INDEX)).thenReturn(Optional.of(rag));
        when(outbox.findTopBySourceTypeAndSourceIdTextOrderByCreatedAtDesc("BLOG", "article-1"))
                .thenReturn(Optional.of(notification));
    }

    private static IndexingJob job(JobType type, JobStatus status, int version) {
        return IndexingJob.builder()
                .jobType(type)
                .status(status)
                .sourceVersion(version)
                .retryCount(0)
                .build();
    }

    private static ContentEventOutbox event(OutboxStatus status, int version) {
        return ContentEventOutbox.builder()
                .status(status)
                .sourceVersion(version)
                .retryCount(0)
                .build();
    }
}
