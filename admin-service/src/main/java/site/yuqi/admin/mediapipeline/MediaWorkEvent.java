package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * "This source version of this asset is stored and may be processed with this profile."
 *
 * <p>Published on the existing work topic {@code content.search.index.v1}. The first eleven
 * fields are the platform's event envelope, the same names and types as
 * {@link site.yuqi.admin.events.ContentIndexEvent}, so the search indexer can read the message and
 * pass over it (it has no {@code indexingJobId}). The last two are for the Media Service. The
 * message is small on purpose: the bytes stay in object storage.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaWorkEvent(
        String eventId,
        Instant occurredAt,
        String traceId,
        String correlationId,
        String causationId,
        int schemaVersion,
        String sourceType,
        String sourceId,
        int sourceVersion,
        String idempotencyKey,
        String jobType,
        String sourceKey,
        String profileVersion) {

    public static final String SOURCE_TYPE = "MEDIA";
    public static final String JOB_TYPE = "MEDIA_PROCESS";

    /** Same shape as the indexing events, so one asset's messages stay in order on one partition. */
    public String partitionKey() {
        return sourceType + ":" + sourceId;
    }
}
