package site.yuqi.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobStatus;
import site.yuqi.admin.domain.JobType;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.events.IndexEventPublisher;
import site.yuqi.admin.security.AdminPrincipal;
import site.yuqi.admin.service.AuditLogService;
import site.yuqi.admin.service.IndexingJobService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/indexing-jobs")
@RequiredArgsConstructor
@Tag(name = "Admin Indexing Jobs", description = "List and retry RAG / SEARCH indexing jobs.")
public class IndexingJobAdminController {

    private final IndexingJobService indexingJobService;
    private final AuditLogService auditLogService;
    private final IndexEventPublisher indexEventPublisher;

    @GetMapping
    @Operation(summary = "List indexing jobs")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "status", required = false) String statusRaw,
            @RequestParam(value = "jobType", required = false) String jobTypeRaw,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "sourceId", required = false) String sourceId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {

        JobStatus status = statusRaw == null ? null : JobStatus.valueOf(statusRaw.toUpperCase());
        JobType jobType = jobTypeRaw == null ? null : JobType.valueOf(jobTypeRaw.toUpperCase());

        return ResponseEntity.ok(Map.of("items",
                indexingJobService.list(status, jobType, sourceType, sourceId, limit, offset)));
    }

    @PostMapping("/{jobId}/retry")
    @Operation(summary = "Retry a FAILED or SKIPPED indexing job")
    public ResponseEntity<IndexingJob> retry(@PathVariable("jobId") UUID jobId,
                                             HttpServletRequest req) {
        IndexingJob job = indexingJobService.retry(jobId);
        SourceType type = SourceType.valueOf(job.getSourceType());
        auditLogService.log(AdminPrincipal.from(req), AuditAction.RETRY_INDEXING_JOB,
                type, job.getSourceIdText(), job.getSourceVersion(),
                null, Map.of("jobId", jobId.toString(), "jobType", job.getJobType().name()));
        indexEventPublisher.publish(job);
        return ResponseEntity.ok(job);
    }
}
