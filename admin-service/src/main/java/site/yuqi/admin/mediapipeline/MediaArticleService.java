package site.yuqi.admin.mediapipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.admin.adapter.ContentAdapterRegistry;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.service.ContentService;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaArticleService {
    public record ArticleView(String sourceType, String sourceId, String title, String contentHash,
                              List<UUID> mediaIds, boolean publicationChanged) { }
    public record Attach(UUID id, String expectedContentHash, String position) { }
    private final ContentAdapterRegistry adapters;
    private final ContentService content;
    private final MediaArticlePolicy policy;

    public MediaArticleService(ContentAdapterRegistry adapters, ContentService content, MediaArticlePolicy policy) {
        this.adapters = adapters; this.content = content; this.policy = policy;
    }

    public ArticleView get(String sourceType, UUID sourceId) {
        return view(content.getOrThrow(type(sourceType), sourceId.toString()));
    }

    @Transactional
    public ArticleView attach(String sourceType, UUID sourceId, Attach request, String actor) {
        if (request == null || request.id() == null) throw new IllegalArgumentException("Video id required");
        var before = lock(sourceType, sourceId, request.expectedContentHash());
        var asset = policy.requireReady(request.id());
        if (!"VIDEO".equals(asset.kind())) throw new IllegalArgumentException("Article video attachment requires VIDEO media");
        return save(before, ArticleMediaMarkup.attach(before.getContent(), request.id(),
                request.position() == null ? "START" : request.position()), actor, "Attach article video");
    }

    @Transactional
    public ArticleView detach(String sourceType, UUID sourceId, UUID id, String hash, String actor) {
        var before = lock(sourceType, sourceId, hash);
        return save(before, ArticleMediaMarkup.detach(before.getContent(), id), actor, "Detach article video");
    }

    private NormalizedContent lock(String rawType, UUID id, String hash) {
        var before = adapters.get(type(rawType)).getForUpdate(id.toString())
                .orElseThrow(() -> new java.util.NoSuchElementException("Article not found"));
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("expectedContentHash from media.get_article is required");
        if (!ArticleMediaMarkup.hash(before.getContent()).equals(hash))
            throw new IllegalStateException("Article changed; read media.get_article before editing again");
        return before;
    }

    private ArticleView save(NormalizedContent before, String html, String actor, String note) {
        if (ArticleMediaMarkup.body(before.getContent()).equals(html)) return view(before);
        return view(content.update(before.getSourceType(), before.getSourceId(), Map.of("content", html), false, actor, note));
    }

    private static SourceType type(String raw) {
        if (!"BLOG".equals(raw) && !"PROJECT".equals(raw)) throw new IllegalArgumentException("sourceType must be BLOG or PROJECT");
        return SourceType.valueOf(raw);
    }

    private static ArticleView view(NormalizedContent row) {
        return new ArticleView(row.getSourceType().name(), row.getSourceId(), row.getTitle(),
                ArticleMediaMarkup.hash(row.getContent()), ArticleMediaMarkup.ids(row.getContent()), false);
    }
}
