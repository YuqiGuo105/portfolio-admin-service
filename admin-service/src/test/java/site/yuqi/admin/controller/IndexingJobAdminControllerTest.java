package site.yuqi.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobStatus;
import site.yuqi.admin.domain.JobType;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.events.IndexEventPublisher;
import site.yuqi.admin.security.AdminPrincipal;
import site.yuqi.admin.service.AuditLogService;
import site.yuqi.admin.service.IndexingJobService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexingJobAdminControllerTest {

    @Mock
    private IndexingJobService indexingJobService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private IndexEventPublisher indexEventPublisher;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private IndexingJobAdminController controller;

    @Test
    void retryRepublishesTheIndexEventAfterResettingTheJob() {
        UUID jobId = UUID.randomUUID();
        IndexingJob job = IndexingJob.builder()
                .id(jobId)
                .sourceType(SourceType.PROJECT.name())
                .sourceIdText("project-123")
                .sourceVersion(2)
                .jobType(JobType.SEARCH_INDEX)
                .status(JobStatus.PENDING)
                .build();

        when(indexingJobService.retry(jobId)).thenReturn(job);
        when(request.getAttribute(AdminPrincipal.ATTR)).thenReturn("test-admin");

        var response = controller.retry(jobId, request);

        assertThat(response.getBody()).isSameAs(job);
        verify(indexingJobService).retry(jobId);
        verify(indexEventPublisher).publish(job);
    }
}
