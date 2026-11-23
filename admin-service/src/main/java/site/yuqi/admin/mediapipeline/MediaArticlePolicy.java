package site.yuqi.admin.mediapipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaArticlePolicy {
    private final MediaAssetStore assets;
    public MediaArticlePolicy(MediaAssetStore assets) { this.assets = assets; }

    MediaAssetStore.AssetRow requireReady(UUID id) {
        var asset = assets.lock(id).orElseThrow(() -> new IllegalArgumentException("Referenced media does not exist"));
        if (asset.deletedAt() != null || !"READY".equals(asset.status()) || asset.manifestJson() == null)
            throw new IllegalStateException("Referenced media must finish processing before attachment or publication");
        return asset;
    }

    public void validatePublication(String html) {
        // Stable lock ordering prevents two publications with overlapping assets from deadlocking.
        ArticleMediaMarkup.ids(html).stream().sorted().forEach(this::requireReady);
    }
}
