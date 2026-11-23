package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.service.AuditLogService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Named placements are independent of article bodies and use processed assets only. */
@Service
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaPresentationService {
    public record Chapter(Double seconds, String title) { }
    public record Replace(UUID id, String expectedRevision, String title, List<Chapter> chapters) { }
    public record View(String placementKey, boolean exists, String revision, UUID assetId,
                       boolean enabled, JsonNode presentation, String pagePath) { }
    private record Row(UUID assetId, boolean enabled, String json, String updatedAt) { }
    private final JdbcTemplate jdbc;
    private final MediaArticlePolicy policy;
    private final AuditLogService audit;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public MediaPresentationService(JdbcTemplate jdbc, MediaArticlePolicy policy, AuditLogService audit,
                                    ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.policy = policy; this.audit = audit; this.mapper = mapper;
        this.tx = new TransactionTemplate(transactions);
    }

    public View get(String key) {
        requireKey(key);
        var rows = jdbc.query("select asset_id, enabled, presentation::text, updated_at::text from media_presentations where placement_key = ?",
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getString(3), rs.getString(4)), key);
        if (rows.isEmpty()) return new View(key, false, ArticleMediaMarkup.hash("missing:" + key), null, false, null, "/");
        var row = rows.getFirst();
        String revision = ArticleMediaMarkup.hash(key + "\n" + row.assetId() + "\n" + row.enabled() + "\n" + row.json() + "\n" + row.updatedAt());
        return new View(key, true, revision, row.assetId(), row.enabled(), json(row.json()), "/");
    }

    public View replace(String key, Replace request, String actor) {
        requireKey(key);
        if (request == null || request.id() == null) throw new IllegalArgumentException("READY video id is required");
        return tx.execute(status -> {
            // The asset lock serializes attachment with deletion; the placement lock also covers first creation.
            var asset = policy.requireReady(request.id());
            if (!"VIDEO".equals(asset.kind())) throw new IllegalArgumentException("Introduction requires VIDEO media");
            var manifest = json(asset.manifestJson());
            requirePlayback(manifest);
            var before = lock(key, request.expectedRevision());
            String title = request.title() == null ? "Yuqi Guo introduction" : request.title().trim();
            if (title.isEmpty() || title.length() > 300) throw new IllegalArgumentException("Title must be 1-300 characters");
            var presentation = mapper.createObjectNode().put("version", 2).put("id", key)
                    .put("playback", "asset").put("title", title);
            var chapters = presentation.putArray("chapters");
            var requested = request.chapters() == null ? List.<Chapter>of() : request.chapters();
            if (requested.size() > 30) throw new IllegalArgumentException("At most 30 chapters allowed");
            double previous = -1;
            for (var chapter : requested) {
                if (chapter == null || chapter.seconds() == null || !Double.isFinite(chapter.seconds())
                        || chapter.seconds() <= previous || chapter.seconds() < 0
                        || chapter.seconds() >= manifest.path("durationSeconds").asDouble()
                        || chapter.title() == null || chapter.title().isBlank() || chapter.title().length() > 120)
                    throw new IllegalArgumentException("Chapters must have ordered timestamps within the video and 1-120 character titles");
                chapters.addObject().put("seconds", chapter.seconds()).put("title", chapter.title().trim());
                previous = chapter.seconds();
            }
            jdbc.update("""
                    insert into media_presentations(placement_key, asset_id, presentation, enabled)
                    values (?, ?, ?::jsonb, true)
                    on conflict (placement_key) do update set asset_id = excluded.asset_id,
                    presentation = excluded.presentation, enabled = true, updated_at = clock_timestamp()
                    """, key, request.id(), presentation.toString());
            var after = get(key);
            audit.log(actor, AuditAction.UPDATE, "MEDIA_PRESENTATION", key, null, snapshot(before), snapshot(after));
            return after;
        });
    }

    public View disable(String key, String expectedRevision, String actor) {
        requireKey(key);
        return tx.execute(status -> {
            var before = lock(key, expectedRevision);
            if (!before.exists() || !before.enabled()) return before;
            jdbc.update("update media_presentations set enabled = false, updated_at = clock_timestamp() where placement_key = ?", key);
            var after = get(key);
            audit.log(actor, AuditAction.UPDATE, "MEDIA_PRESENTATION", key, null, snapshot(before), snapshot(after));
            return after;
        });
    }

    private View lock(String key, String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("expectedRevision from media.get_presentation is required");
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "media-presentation:" + key);
        var before = get(key);
        if (!before.revision().equals(revision)) throw new IllegalStateException("Placement changed; read media.get_presentation again");
        return before;
    }

    static void requireKey(String key) {
        if (!"yuqi-intro".equals(key)) throw new IllegalArgumentException("Unsupported placementKey; supported: yuqi-intro");
    }

    static void requirePlayback(JsonNode manifest) {
        String base = manifest.path("basePath").asText();
        if (!base.matches("media/[a-zA-Z0-9/_-]+") || base.contains("//")
                || !playlist(manifest.path("hls").asText()) || !relative(manifest.path("poster").path("path").asText())
                || !Double.isFinite(manifest.path("durationSeconds").asDouble()) || manifest.path("durationSeconds").asDouble() <= 0
                || !manifest.path("renditions").isArray() || manifest.path("renditions").isEmpty())
            throw new IllegalStateException("Processed video must include a valid HLS manifest, poster, duration and renditions");
        for (var rendition : manifest.path("renditions"))
            if (!playlist(rendition.path("playlist").asText()) || rendition.path("height").asInt() <= 0 || rendition.path("width").asInt() <= 0)
                throw new IllegalStateException("Invalid processed video rendition");
    }

    private static boolean playlist(String path) { return relative(path) && path.endsWith(".m3u8"); }

    private static boolean relative(String path) {
        return path.matches("[a-zA-Z0-9/_ .-]+") && !path.startsWith("/")
                && java.util.Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isEmpty() || p.equals("..") || p.equals("."));
    }

    private JsonNode json(String text) {
        try { return mapper.readTree(text); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored media manifest"); }
    }

    private static Map<String, Object> snapshot(View view) {
        return Map.of("assetId", view.assetId() == null ? "" : view.assetId().toString(), "enabled", view.enabled(),
                "revision", view.revision(), "presentation", view.presentation() == null ? Map.of() : view.presentation());
    }
}
