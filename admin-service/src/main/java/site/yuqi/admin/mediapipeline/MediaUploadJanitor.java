package site.yuqi.admin.mediapipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes upload sessions that were started and never finished: a closed tab, a lost connection.
 * The parts already uploaded are deleted from the bucket, so abandoned uploads do not keep costing
 * storage, and the asset ends as FAILED with a reason instead of staying UPLOADING forever.
 */
@Component
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaUploadJanitor {

    private final MediaAssetStore assets;
    private final MediaUploadService uploads;

    public MediaUploadJanitor(MediaAssetStore assets, MediaUploadService uploads) {
        this.assets = assets;
        this.uploads = uploads;
    }

    @Scheduled(fixedDelayString = "${portfolio.media-pipeline.upload.janitor-interval-ms:600000}",
            initialDelayString = "${portfolio.media-pipeline.upload.janitor-initial-delay-ms:60000}")
    public void expireAbandonedUploads() {
        assets.findExpiredUploads(50).forEach(uploads::expire);
    }
}
