package site.yuqi.admin.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Event payload published to Kafka topics
 *   {@code content.search.index.v1}  (consumed by portfolio-search-indexer)
 *   {@code content.rag.index.v1}     (consumed by portfolio-rag-indexer)
 *
 * Shape is shared by all three services. Indexers read {@code sourceType}
 * + {@code sourceId} to pull the current row, and report status back by
 * updating {@code indexingJobId} in {@code public.indexing_jobs}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentIndexEvent {
    /** Unique event identifier. Workers MAY use this for idempotency. */
    private String eventId;

    /** When the event was produced. */
    private Instant occurredAt;

    /** BLOG / PROJECT / LIFE_BLOG / EXPERIENCE */
    private String sourceType;

    /** Source-row id, as text (UUID or bigint). */
    private String sourceId;

    /** Version of the content snapshot this event refers to. */
    private int sourceVersion;

    /**
     * Reference to the row in {@code public.indexing_jobs}. Workers update
     * its status (PROCESSING/DONE/FAILED).
     */
    private String indexingJobId;

    /**
     * Same idempotency key written on the indexing_jobs row, e.g.
     * {@code SEARCH_INDEX:BLOG:<uuid>:v3}.
     */
    private String idempotencyKey;

    /** Either {@code SEARCH_INDEX} or {@code RAG_INDEX}. */
    private String jobType;
}
