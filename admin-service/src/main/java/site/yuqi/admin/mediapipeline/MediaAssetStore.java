package site.yuqi.admin.mediapipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for the Content Service's half of the media tables: upload sessions, the source binding,
 * and the outbox. The Media Service owns the job rows and the publish pointer.
 */
@Repository
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaAssetStore {

    /** A media_assets row as the upload API sees it. JSON columns stay text; the controller passes them through. */
    public record AssetRow(
            UUID id, String kind, String status, String title, String originalFilename, String contentType,
            long sizeBytes, String uploadId, String storageUploadId, String uploadKey, Long partSizeBytes,
            Integer partCount, Instant uploadExpiresAt, int sourceVersion, String sourceKey, String profileVersion,
            String manifestJson, String failureCode, String failureMessage, Instant createdAt, Instant updatedAt) {
    }

    public record JobRow(String jobType, String rendition, String status, int attempt, String lastError) {
    }

    public record OutboxRow(UUID id, String eventId, UUID assetId, int sourceVersion, String partitionKey,
                            String payloadJson, int attempts) {
    }

    private static final String COLUMNS = """
            id, kind, status, title, original_filename, content_type, size_bytes, upload_id, storage_upload_id,
            upload_key, part_size_bytes, part_count, upload_expires_at, source_version, source_key,
            profile_version, manifest::text as manifest, failure_code, failure_message, created_at, updated_at
            """;

    private static final RowMapper<AssetRow> ASSET = (rs, n) -> new AssetRow(
            rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("status"), rs.getString("title"),
            rs.getString("original_filename"), rs.getString("content_type"), rs.getLong("size_bytes"),
            rs.getString("upload_id"), rs.getString("storage_upload_id"), rs.getString("upload_key"),
            (Long) rs.getObject("part_size_bytes"), (Integer) rs.getObject("part_count"),
            instant(rs.getObject("upload_expires_at", OffsetDateTime.class)), rs.getInt("source_version"),
            rs.getString("source_key"), rs.getString("profile_version"), rs.getString("manifest"),
            rs.getString("failure_code"), rs.getString("failure_message"),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("updated_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;

    public MediaAssetStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── upload sessions ───────────────────────────────────────────────────────

    public void insertUploading(UUID id, String kind, String title, String filename, String contentType,
                                long sizeBytes, String uploadId, String storageUploadId, String uploadKey,
                                long partSizeBytes, int partCount, int sessionTtlSeconds, String createdBy) {
        jdbc.update("""
                insert into media_assets (id, kind, status, title, original_filename, content_type, size_bytes,
                                          upload_id, storage_upload_id, upload_key, part_size_bytes, part_count,
                                          upload_expires_at, created_by)
                values (?, ?, 'UPLOADING', ?, ?, ?, ?, ?, ?, ?, ?, ?, now() + make_interval(secs => ?), ?)
                """, id, kind, title, filename, contentType, sizeBytes, uploadId, storageUploadId, uploadKey,
                partSizeBytes, partCount, sessionTtlSeconds, createdBy);
    }

    public Optional<AssetRow> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from media_assets where id = ?", ASSET, id).stream().findFirst();
    }

    public List<AssetRow> list(String status, String kind, int limit) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from media_assets where true");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" and status = ?");
            args.add(status);
        }
        if (kind != null) {
            sql.append(" and kind = ?");
            args.add(kind);
        }
        sql.append(" order by created_at desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), ASSET, args.toArray());
    }

    public List<JobRow> jobs(UUID assetId, int sourceVersion) {
        return jdbc.query("""
                select job_type, rendition, status, attempt, last_error from media_jobs
                 where asset_id = ? and source_version = ? order by created_at, rendition
                """, (rs, n) -> new JobRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5)), assetId, sourceVersion);
    }

    /**
     * Step 2 of the pipeline, the binding: this upload becomes the asset's next source version and
     * the asset moves to PROCESSING. Guarded by status and upload id, so of two concurrent
     * completions for the same upload exactly one wins.
     */
    public boolean bindSource(UUID id, String uploadId, String etag, long bytes, String profileVersion) {
        return jdbc.update("""
                update media_assets
                   set status = 'PROCESSING', source_version = source_version + 1, source_key = upload_key,
                       source_etag = ?, source_bytes = ?, profile_version = ?, processing_started_at = now(),
                       storage_upload_id = null, failure_code = null, failure_message = null, updated_at = now()
                 where id = ? and status = 'UPLOADING' and upload_id = ?
                """, etag, bytes, profileVersion, id, uploadId) == 1;
    }

    /** Uploads nobody finished. Returned so the caller can abort them in the store as well. */
    public List<AssetRow> findExpiredUploads(int limit) {
        return jdbc.query("select " + COLUMNS + " from media_assets where status = 'UPLOADING' "
                + "and upload_expires_at < now() order by upload_expires_at limit ?", ASSET, limit);
    }

    public boolean failUpload(UUID id, String uploadId, String code, String message) {
        return jdbc.update("""
                update media_assets
                   set status = 'FAILED', failure_code = ?, failure_message = ?, storage_upload_id = null, updated_at = now()
                 where id = ? and status = 'UPLOADING' and upload_id = ?
                """, code, message, id, uploadId) == 1;
    }

    /** A failed asset goes back to PROCESSING and its ended jobs become runnable again. */
    public boolean reopenFailed(UUID id, int sourceVersion) {
        boolean reopened = jdbc.update("""
                update media_assets
                   set status = 'PROCESSING', failure_code = null, failure_message = null,
                       processing_started_at = now(), updated_at = now()
                 where id = ? and status = 'FAILED' and source_version = ? and source_version > 0
                """, id, sourceVersion) == 1;
        if (reopened) {
            jdbc.update("""
                    update media_jobs
                       set status = 'PENDING', attempt = 0, last_error = null, next_attempt_at = now(),
                           finished_at = null, updated_at = now()
                     where asset_id = ? and source_version = ? and status = 'DEAD'
                    """, id, sourceVersion);
        }
        return reopened;
    }

    // ── outbox ────────────────────────────────────────────────────────────────

    /** Must run in the same transaction as the change the event announces. */
    public void insertOutbox(String eventId, String eventType, UUID assetId, int sourceVersion,
                             String partitionKey, String payloadJson) {
        jdbc.update("""
                insert into media_event_outbox (id, event_id, event_type, asset_id, source_version, partition_key, payload)
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), eventId, eventType, assetId, sourceVersion, partitionKey, payloadJson);
    }

    /**
     * Leases events that are due. A crashed relay leaves its events PROCESSING with a lease that
     * runs out, after which any relay picks them up again.
     */
    public List<OutboxRow> claimOutbox(int limit, int leaseSeconds) {
        return jdbc.query("""
                with due as (
                    select id from media_event_outbox
                     where status in ('PENDING', 'PROCESSING') and next_attempt_at <= now()
                       and (lease_expires_at is null or lease_expires_at < now())
                     order by created_at
                     limit ?
                       for update skip locked
                )
                update media_event_outbox o
                   set status = 'PROCESSING', attempts = o.attempts + 1,
                       lease_expires_at = now() + make_interval(secs => ?)
                  from due
                 where o.id = due.id
                returning o.id, o.event_id, o.asset_id, o.source_version, o.partition_key,
                          o.payload::text as payload, o.attempts
                """, (rs, n) -> new OutboxRow(rs.getObject("id", UUID.class), rs.getString("event_id"),
                rs.getObject("asset_id", UUID.class), rs.getInt("source_version"), rs.getString("partition_key"),
                rs.getString("payload"), rs.getInt("attempts")), limit, leaseSeconds);
    }

    public void markOutboxSent(UUID id) {
        jdbc.update("update media_event_outbox set status = 'SENT', sent_at = now(), lease_expires_at = null, "
                + "last_error = null where id = ?", id);
    }

    /** Back to PENDING after a delay; parked as DLQ once it has been tried {@code maxAttempts} times. */
    public void markOutboxFailed(UUID id, String error, long retryInSeconds, int maxAttempts) {
        jdbc.update("""
                update media_event_outbox
                   set status = case when attempts >= ? then 'DLQ' else 'PENDING' end,
                       next_attempt_at = now() + make_interval(secs => ?), lease_expires_at = null, last_error = ?
                 where id = ?
                """, maxAttempts, retryInSeconds, error == null ? null : error.substring(0, Math.min(2000, error.length())), id);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
