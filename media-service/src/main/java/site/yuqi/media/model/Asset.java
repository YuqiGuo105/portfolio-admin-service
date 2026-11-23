package site.yuqi.media.model;

import java.util.UUID;

/** The part of a media_assets row the Media Service works with. */
public record Asset(
        UUID id,
        String kind,
        String status,
        String contentType,
        int sourceVersion,
        String sourceKey,
        String profileVersion,
        String probeJson) {

    public static final String VIDEO = "VIDEO";
    public static final String IMAGE = "IMAGE";

    public static final String PROCESSING = "PROCESSING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";

    public boolean isVideo() {
        return VIDEO.equals(kind);
    }
}
