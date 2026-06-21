package site.yuqi.ragindexer.source;

import lombok.Builder;
import lombok.Data;

/** Plain DTO containing everything needed to chunk + embed a source row. */
@Data
@Builder
public class RagSource {
    private String sourceType;   // BLOG / PROJECT / LIFE_BLOG / EXPERIENCE
    private String sourceId;
    private String title;
    private String summary;
    private String content;      // the long text to embed
    private String url;          // canonical site URL for citation
}
