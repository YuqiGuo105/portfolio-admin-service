package site.yuqi.media.intake;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * The message on the shared work topic ({@code content.search.index.v1}).
 *
 * <p>It is the platform's existing event envelope, so the search indexer can read past it, plus two
 * media fields. It says "this source version of this asset is stored and may be processed with this
 * profile". The bytes stay in object storage; the message only carries identity and location.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkEvent(
        String eventId,
        Instant occurredAt,
        String traceId,
        String correlationId,
        String causationId,
        Integer schemaVersion,
        String sourceType,
        String sourceId,
        Integer sourceVersion,
        String indexingJobId,
        String idempotencyKey,
        String jobType,
        String sourceKey,
        String profileVersion) {

    public static final String SOURCE_TYPE = "MEDIA";
    public static final String JOB_TYPE = "MEDIA_PROCESS";

    /** Everything else on the topic belongs to the indexers. */
    public boolean isMediaWork() {
        return JOB_TYPE.equals(jobType) && SOURCE_TYPE.equals(sourceType);
    }
}
