package site.yuqi.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.AuditLog;
import site.yuqi.admin.domain.ContentVersion;
import site.yuqi.admin.domain.IndexingJob;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentDetailDto {
    private NormalizedContent content;
    private ContentVersion latestVersion;
    private List<IndexingJob> recentIndexingJobs;
    private List<AuditLog> recentAuditLogs;
}
