package site.yuqi.admin.mediapipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.yuqi.admin.domain.AdminUserRole;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.service.AdminUserService;
import site.yuqi.admin.service.AuditLogService;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaPresentationJobs {
    private static final Logger log = LoggerFactory.getLogger(MediaPresentationJobs.class);
    public record Prepare(UUID requestId, String placementKey, String title) { }
    public record View(UUID id, String placementKey, String status, UUID assetId, String assetStatus,
                       UUID previousAssetId, String title, String errorCode, String resultRevision,
                       Instant createdAt, Instant expiresAt, String uploadUrl) { }
    private record Job(View view, String expectedRevision, String approvedBy) { }
    private static final String SELECT = """
        select j.*, a.status as asset_status from media_presentation_jobs j
        left join media_assets a on a.id = j.asset_id
        """;
    private final JdbcTemplate jdbc;
    private final MediaUploadService uploads;
    private final MediaAssetStore assets;
    private final MediaPresentationService presentations;
    private final AdminUserService users;
    private final AuditLogService audit;
    private final List<String> allowedEmails;
    private final TransactionTemplate tx;

    public MediaPresentationJobs(JdbcTemplate jdbc, MediaUploadService uploads, MediaAssetStore assets,
                                MediaPresentationService presentations, AdminUserService users, AuditLogService audit,
                                PlatformTransactionManager transactions,
                                @Value("${portfolio.supabase.allowed-emails:}") String allowedEmailsCsv) {
        this.jdbc = jdbc; this.uploads = uploads; this.assets = assets; this.presentations = presentations;
        this.users = users; this.audit = audit; this.tx = new TransactionTemplate(transactions);
        this.allowedEmails = Arrays.stream(allowedEmailsCsv.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    public View prepare(Prepare request, String actor) {
        if (request == null || request.requestId() == null) throw new IllegalArgumentException("requestId is required");
        MediaPresentationService.requireKey(request.placementKey());
        String title = request.title() == null ? "Yuqi Guo introduction" : request.title().trim();
        if (title.isEmpty() || title.length() > 300) throw new IllegalArgumentException("Title must be 1-300 characters");
        return tx.execute(ignored -> {
            jdbc.query("select pg_advisory_xact_lock(hashtextextended('media-presentation-jobs', 0))", rs -> { });
            var existing = find(request.requestId(), false);
            if (existing.isPresent()) {
                var view = existing.get().view();
                if (!view.placementKey().equals(request.placementKey()) || !view.title().equals(title))
                    throw new IllegalStateException("requestId already belongs to a different upload request");
                return view;
            }
            int pending = jdbc.queryForObject("select count(*) from media_presentation_jobs where status in "
                    + "('WAITING_UPLOAD','WAITING_MEDIA') and expires_at > now()", Integer.class);
            if (pending >= 20) throw new IllegalStateException("Too many pending uploads; cancel an existing request first");
            var before = presentations.get(request.placementKey());
            jdbc.update("""
                insert into media_presentation_jobs(id,placement_key,status,expected_revision,previous_asset_id,title,created_by)
                values (?,?,'WAITING_UPLOAD',?,?,?,?)
                """, request.requestId(), request.placementKey(), before.revision(), before.assetId(), title, actor);
            audit.log(actor, AuditAction.CREATE, "MEDIA_PRESENTATION_JOB", request.requestId().toString(), null,
                    Map.of(), Map.of("placementKey", request.placementKey(), "status", "WAITING_UPLOAD"));
            return get(request.requestId());
        });
    }

    public View get(UUID id) { return find(id, false).orElseThrow(() -> new NoSuchElementException("Upload request not found")).view(); }

    public List<View> list() {
        return jdbc.query(SELECT + " order by j.created_at desc limit 20", (rs, n) -> row(rs).view());
    }

    public MediaUploadService.UploadSession upload(UUID id, MediaUploadService.StartUpload request, String email, String actor) {
        if (!administrator(email)) throw new IllegalArgumentException("Sign in with an authorized administrator account to upload");
        if (request == null || request.contentType() == null || !request.contentType().startsWith("video/"))
            throw new IllegalArgumentException("Introduction requires a video file");
        return tx.execute(ignored -> {
            var job = requireLocked(id);
            if (!pending(job.view()) || !job.view().expiresAt().isAfter(Instant.now()))
                throw new IllegalStateException("Upload request is closed or expired; create a new request");
            if (!presentations.get(job.view().placementKey()).revision().equals(job.expectedRevision()))
                throw new IllegalStateException("Introduction changed since this request was created; create a new request");
            if (job.view().assetId() != null) {
                var asset = assets.find(job.view().assetId()).orElseThrow();
                if (!asset.originalFilename().equals(MediaUploadService.safeName(request.filename()))
                        || asset.sizeBytes() != request.sizeBytes() || !asset.contentType().equals(request.contentType()))
                    throw new IllegalStateException("Select the original file to resume, or create a new upload request");
                return uploads.resume(asset.id(), asset.uploadId());
            }
            var session = uploads.start(new MediaUploadService.StartUpload("VIDEO", request.filename(),
                    request.contentType(), request.sizeBytes(), job.view().title()), actor);
            jdbc.update("update media_presentation_jobs set asset_id=?,approved_by=?,status='WAITING_MEDIA',updated_at=now() where id=?",
                    session.assetId(), email, id);
            audit.log(actor, AuditAction.UPDATE, "MEDIA_PRESENTATION_JOB", id.toString(), null,
                    Map.of("status", "WAITING_UPLOAD"), Map.of("assetId", session.assetId(), "approvedBy", email));
            return session;
        });
    }

    public View cancel(UUID id, String actor) {
        return tx.execute(ignored -> {
            var job = requireLocked(id);
            if (pending(job.view())) {
                finish(id, "CANCELLED", null, null);
                audit.log(actor, AuditAction.UPDATE, "MEDIA_PRESENTATION_JOB", id.toString(), null,
                        Map.of("status", job.view().status()), Map.of("status", "CANCELLED"));
            }
            return get(id);
        });
    }

    // Called by the existing durable media recovery request, never by a read endpoint or browser timer.
    public void reconcile() {
        var ids = jdbc.queryForList("""
            select j.id from media_presentation_jobs j left join media_assets a on a.id=j.asset_id
            where j.status in ('WAITING_UPLOAD','WAITING_MEDIA') and
                (j.expires_at <= now() or a.status in ('READY','FAILED') or a.deleted_at is not null)
            order by j.created_at limit 20
            """, UUID.class);
        for (UUID id : ids) {
            try { tx.executeWithoutResult(ignored -> apply(id)); }
            catch (RuntimeException error) {
                // One failed transaction must not prevent recovery of other requests.
                log.warn("Introduction job {} will retry after {}", id, error.getClass().getSimpleName());
            }
        }
    }

    private void apply(UUID id) {
        var job = requireLocked(id);
        if (!pending(job.view())) return;
        if (!job.view().expiresAt().isAfter(Instant.now())) { finish(id, "EXPIRED", "request_expired", null); return; }
        if (job.view().assetId() == null) return;
        var asset = assets.lock(job.view().assetId()).orElseThrow();
        if (asset.deletedAt() != null || "FAILED".equals(asset.status())) { finish(id, "FAILED", "media_processing_failed", null); return; }
        if (!"READY".equals(asset.status())) return;
        if (!administrator(job.approvedBy())) { finish(id, "FAILED", "administrator_access_revoked", null); return; }
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "media-presentation:" + job.view().placementKey());
        if (!presentations.get(job.view().placementKey()).revision().equals(job.expectedRevision())) {
            finish(id, "CONFLICT", "introduction_changed", null); return;
        }
        // replace() validates all processed outputs and writes its audit in this same transaction.
        // On infrastructure/audit failure the transaction rolls back; recovery can retry safely.
        var result = presentations.replace(job.view().placementKey(), new MediaPresentationService.Replace(
                asset.id(), job.expectedRevision(), job.view().title(), List.of()), job.approvedBy());
        finish(id, "SUCCEEDED", null, result.revision());
    }

    private boolean administrator(String email) {
        if (email == null || !email.contains("@")) return false;
        var auth = users.authorize(email, allowedEmails);
        return auth != null && auth.allowed() && auth.role() == AdminUserRole.ADMIN;
    }
    private static boolean pending(View view) { return Set.of("WAITING_UPLOAD", "WAITING_MEDIA").contains(view.status()); }
    private void finish(UUID id, String status, String error, String revision) {
        jdbc.update("update media_presentation_jobs set status=?,error_code=?,result_revision=?,updated_at=now() where id=?", status, error, revision, id);
    }
    private Job requireLocked(UUID id) { return find(id, true).orElseThrow(() -> new NoSuchElementException("Upload request not found")); }
    private Optional<Job> find(UUID id, boolean lock) {
        return jdbc.query(SELECT + " where j.id=?" + (lock ? " for update of j" : ""), (rs, n) -> row(rs), id).stream().findFirst();
    }
    private static Job row(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Job(new View(id, rs.getString("placement_key"), rs.getString("status"),
                rs.getObject("asset_id", UUID.class), rs.getString("asset_status"), rs.getObject("previous_asset_id", UUID.class),
                rs.getString("title"), rs.getString("error_code"), rs.getString("result_revision"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                "https://www.yuqi.site/admin/media-intro?job=" + id), rs.getString("expected_revision"), rs.getString("approved_by"));
    }
}
