package site.yuqi.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.ContentVersion;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobType;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.dto.ContentDetailDto;
import site.yuqi.admin.dto.ContentListItemDto;
import site.yuqi.admin.dto.ContentMutationRequest;
import site.yuqi.admin.dto.PublishRequest;
import site.yuqi.admin.dto.PublishResponseDto;
import site.yuqi.admin.repo.ContentVersionRepository;
import site.yuqi.admin.security.AdminPrincipal;
import site.yuqi.admin.service.AuditLogService;
import site.yuqi.admin.service.ContentService;
import site.yuqi.admin.service.IndexingJobService;
import site.yuqi.admin.service.VersionService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/content")
@RequiredArgsConstructor
@Tag(name = "Admin Content", description = "CRUD and publish workflow for portfolio content sources.")
public class ContentAdminController {

    private final ContentService contentService;
    private final VersionService versionService;
    private final IndexingJobService indexingJobService;
    private final AuditLogService auditLogService;
    private final ContentVersionRepository versionRepository;

    @GetMapping
    @Operation(summary = "List content across one or all sources",
            description = "If no type is provided, results are merged across all four source types.")
    public ResponseEntity<Map<String, Object>> list(
            @Parameter(description = "BLOG | PROJECT | LIFE_BLOG | EXPERIENCE")
            @RequestParam(value = "type", required = false) String typeRaw,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        SourceType type = typeRaw == null ? null : SourceType.parse(typeRaw)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sourceType: " + typeRaw));

        List<NormalizedContent> items = contentService.listAll(type, keyword, category, limit, offset);
        List<ContentListItemDto> dtos = new ArrayList<>(items.size());

        for (NormalizedContent c : items) {
            ContentListItemDto dto = ContentListItemDto.fromNormalized(c);
            dto.setLatestVersion(versionService.latestVersionFor(c.getSourceType().name(), c.getSourceId()));
            indexingJobService.latestJob(c.getSourceType(), c.getSourceId(), JobType.RAG_INDEX)
                    .ifPresent(j -> dto.setRagStatus(j.getStatus()));
            indexingJobService.latestJob(c.getSourceType(), c.getSourceId(), JobType.SEARCH_INDEX)
                    .ifPresent(j -> dto.setSearchStatus(j.getStatus()));
            versionRepository
                    .findTopBySourceTypeAndSourceIdTextOrderByVersionDesc(c.getSourceType().name(), c.getSourceId())
                    .ifPresent(v -> dto.setUpdatedAt(v.getCreatedAt()));
            dtos.add(dto);
        }
        return ResponseEntity.ok(Map.of("items", dtos));
    }

    @GetMapping("/{sourceType}/{sourceId}")
    @Operation(summary = "Get a single content item by sourceType + sourceId")
    public ResponseEntity<ContentDetailDto> get(@PathVariable String sourceType,
                                                @PathVariable String sourceId) {
        SourceType type = SourceType.parse(sourceType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sourceType: " + sourceType));

        NormalizedContent content = contentService.getOrThrow(type, sourceId);
        ContentVersion latest = versionRepository
                .findTopBySourceTypeAndSourceIdTextOrderByVersionDesc(type.name(), sourceId)
                .orElse(null);

        List<IndexingJob> recentJobs = new ArrayList<>();
        indexingJobService.latestJob(type, sourceId, JobType.RAG_INDEX).ifPresent(recentJobs::add);
        indexingJobService.latestJob(type, sourceId, JobType.SEARCH_INDEX).ifPresent(recentJobs::add);

        return ResponseEntity.ok(ContentDetailDto.builder()
                .content(content)
                .latestVersion(latest)
                .recentIndexingJobs(recentJobs)
                .recentAuditLogs(auditLogService.recentFor(type, sourceId, 20))
                .build());
    }

    @PostMapping("/{sourceType}")
    @Operation(summary = "Create a new content item")
    public ResponseEntity<NormalizedContent> create(@PathVariable String sourceType,
                                                    @RequestBody ContentMutationRequest body,
                                                    HttpServletRequest req) {
        SourceType type = SourceType.parse(sourceType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sourceType: " + sourceType));
        Map<String, Object> data = body == null || body.getData() == null ? new HashMap<>() : body.getData();
        boolean publish = body != null && body.isPublish();
        String changeNote = body == null ? null : body.getChangeNote();

        NormalizedContent created = contentService.create(type, data, publish,
                AdminPrincipal.from(req), changeNote);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{sourceType}/{sourceId}")
    @Operation(summary = "Update a content item")
    public ResponseEntity<NormalizedContent> update(@PathVariable String sourceType,
                                                    @PathVariable String sourceId,
                                                    @RequestBody ContentMutationRequest body,
                                                    HttpServletRequest req) {
        SourceType type = SourceType.parse(sourceType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sourceType: " + sourceType));
        Map<String, Object> data = body == null || body.getData() == null ? new HashMap<>() : body.getData();
        boolean publish = body != null && body.isPublish();
        String changeNote = body == null ? null : body.getChangeNote();

        NormalizedContent updated = contentService.update(type, sourceId, data, publish,
                AdminPrincipal.from(req), changeNote);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{sourceType}/{sourceId}/publish")
    @Operation(summary = "Publish (snapshot + outbox + indexing jobs)")
    public ResponseEntity<PublishResponseDto> publish(@PathVariable String sourceType,
                                                      @PathVariable String sourceId,
                                                      @RequestBody(required = false) PublishRequest body,
                                                      HttpServletRequest req) {
        SourceType type = SourceType.parse(sourceType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sourceType: " + sourceType));
        String changeNote = body == null ? null : body.getChangeNote();
        return ResponseEntity.ok(PublishResponseDto.from(
                contentService.publish(type, sourceId, AdminPrincipal.from(req), changeNote)));
    }

    @PostMapping("/{sourceType}/{sourceId}/reindex-rag")
    @Operation(summary = "Enqueue a manual RAG_INDEX job for this content")
    public ResponseEntity<IndexingJob> reindexRag(@PathVariable String sourceType,
                                                  @PathVariable String sourceId,
                                                  HttpServletRequest req) {
        SourceType type = SourceType.parse(sourceType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sourceType: " + sourceType));
        return ResponseEntity.ok(contentService.enqueueManualReindex(
                JobType.RAG_INDEX, type, sourceId, AdminPrincipal.from(req)));
    }

    @PostMapping("/{sourceType}/{sourceId}/reindex-search")
    @Operation(summary = "Enqueue a manual SEARCH_INDEX job for this content")
    public ResponseEntity<IndexingJob> reindexSearch(@PathVariable String sourceType,
                                                     @PathVariable String sourceId,
                                                     HttpServletRequest req) {
        SourceType type = SourceType.parse(sourceType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sourceType: " + sourceType));
        return ResponseEntity.ok(contentService.enqueueManualReindex(
                JobType.SEARCH_INDEX, type, sourceId, AdminPrincipal.from(req)));
    }
}
