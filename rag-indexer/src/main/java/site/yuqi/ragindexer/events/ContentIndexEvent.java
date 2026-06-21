package site.yuqi.ragindexer.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Mirror of {@code site.yuqi.admin.events.ContentIndexEvent}. Keep field
 * names in sync with admin-service and search-indexer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentIndexEvent {
    private String eventId;
    private Instant occurredAt;
    private String sourceType;       // BLOG / PROJECT / LIFE_BLOG / EXPERIENCE
    private String sourceId;
    private int sourceVersion;
    private String indexingJobId;
    private String idempotencyKey;
    private String jobType;          // RAG_INDEX
}
