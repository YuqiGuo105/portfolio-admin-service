package site.yuqi.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.JobStatus;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentListItemDto {
    private String sourceType;
    private String sourceId;
    private String title;
    private String summary;
    private String category;
    private List<String> tags;
    private String url;
    private Integer latestVersion;
    private JobStatus ragStatus;
    private JobStatus searchStatus;
    private Object updatedAt;     // populated from version row when present

    public static ContentListItemDto fromNormalized(NormalizedContent c) {
        return ContentListItemDto.builder()
                .sourceType(c.getSourceType().name())
                .sourceId(c.getSourceId())
                .title(c.getTitle())
                .summary(c.getSummary())
                .category(c.getCategory())
                .tags(c.getTags())
                .url(c.getUrl())
                .build();
    }
}
