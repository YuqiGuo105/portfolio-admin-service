package site.yuqi.searchindexer.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Mirror of {@code site.yuqi.admin.events.ContentIndexEvent} on the
 * producer side. Schema is shared by all 3 services. Keep field names in
 * sync with admin-service and rag-indexer.
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
    private String jobType;          // SEARCH_INDEX
}
