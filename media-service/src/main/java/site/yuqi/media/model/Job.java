package site.yuqi.media.model;

import java.util.Set;
import java.util.UUID;

/**
 * One unit of work in media_jobs.
 *
 * <p>{@code attempt} is the fencing number: it goes up every time the job is leased, each attempt
 * writes under its own {@code a{attempt}/} directory, and a result is only accepted while the row
 * still carries the same attempt and owner.
 */
public record Job(
        UUID id,
        UUID assetId,
        int sourceVersion,
        String profileVersion,
        String jobType,
        String rendition,
        String specJson,
        String status,
        int attempt,
        int maxAttempts,
        String leaseOwner,
        String outputJson) {

    public static final String PLAN = "PLAN";
    public static final String VIDEO_RENDITION = "VIDEO_RENDITION";
    public static final String VIDEO_MP4 = "VIDEO_MP4";
    public static final String VIDEO_POSTER = "VIDEO_POSTER";
    public static final String IMAGE_VARIANTS = "IMAGE_VARIANTS";
    public static final String PUBLISH = "PUBLISH";

    /** Jobs that produce files. PLAN creates them, PUBLISH waits for all of them. */
    public static final Set<String> OUTPUT_TYPES = Set.of(VIDEO_RENDITION, VIDEO_MP4, VIDEO_POSTER, IMAGE_VARIANTS);

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String DEAD = "DEAD";

    /** Where this attempt, and only this attempt, may write. */
    public String outputPrefix() {
        return "media/" + assetId + "/" + sourceVersion + "/" + profileVersion + "/a" + attempt + "/" + rendition;
    }

    /** The directory every output of this source version and profile lives under. */
    public String basePath() {
        return "media/" + assetId + "/" + sourceVersion + "/" + profileVersion;
    }
}
