package site.yuqi.admin.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import site.yuqi.admin.domain.SourceType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter-normalized view of content from any source table. Adapters are the
 * single point that knows how to translate between {@link NormalizedContent}
 * and the underlying inconsistent Supabase tables.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedContent {
    private SourceType sourceType;
    private String sourceId;
    private String title;
    private String summary;
    private String content;
    private String category;
    private List<String> tags;
    private String imageUrl;
    private String url;
    /** Raw row attributes, useful for audit snapshots and worker enrichment. */
    private Map<String, Object> raw;

    public Map<String, Object> rawOrEmpty() {
        return raw == null ? new HashMap<>() : raw;
    }
}
