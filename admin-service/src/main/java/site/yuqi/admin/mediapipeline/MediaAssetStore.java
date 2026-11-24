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
import java.util.Map;
import java.util.HashMap;

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
            String manifestJson, String failureCode, String failureMessage, Instant createdAt, Instant updatedAt,
            String probeJson, String description, int metadataVersion, Instant deletedAt,
            String originalStatus, Instant originalDeletedAt, String originalStorageEndpoint,
            String originalStorageBucket, String originalStorageGeneration, String sourceEtag, Long sourceBytes) {
    }

    public record OriginalDeletion(UUID assetId, int sourceVersion, String sourceKey, String storageBucket,
                                   String storageEndpoint, String storageGeneration,
                                   int expectedMetadataVersion, String requestedBy) { }

    public record JobRow(String jobType, String rendition, String status, int attempt, String lastError,
                         int maxAttempts, Instant startedAt, Instant finishedAt) {
    }

    public record OutboxRow(UUID id, String eventId, UUID assetId, int sourceVersion, String partitionKey,
                            String payloadJson, int attempts) {
    }

    private static final String COLUMNS = """
            id, kind, status, title, original_filename, content_type, size_bytes, upload_id, storage_upload_id,
            upload_key, part_size_bytes, part_count, upload_expires_at, source_version, source_key,
            profile_version, manifest::text as manifest, failure_code, failure_message, created_at, updated_at,
            probe::text as probe, description, metadata_version, deleted_at, original_status, original_deleted_at,
            original_storage_endpoint, original_storage_bucket, original_storage_generation, source_etag, source_bytes
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
            instant(rs.getObject("updated_at", OffsetDateTime.class)), rs.getString("probe"),
            rs.getString("description"), rs.getInt("metadata_version"),
            instant(rs.getObject("deleted_at", OffsetDateTime.class)), rs.getString("original_status"),
            instant(rs.getObject("original_deleted_at", OffsetDateTime.class)), rs.getString("original_storage_endpoint"),
            rs.getString("original_storage_bucket"), rs.getString("original_storage_generation"),
            rs.getString("source_etag"), (Long) rs.getObject("source_bytes"));

    private final JdbcTemplate jdbc;

    public MediaAssetStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── upload sessions ───────────────────────────────────────────────────────

    public void insertUploading(UUID id, String kind, String title, String filename, String contentType,
                                long sizeBytes, String uploadId, String storageUploadId, String uploadKey,
                                long partSizeBytes, int partCount, int sessionTtlSeconds, String createdBy,
                                String storageEndpoint, String storageBucket) {
        jdbc.update("""
                insert into media_assets (id, kind, status, title, original_filename, content_type, size_bytes,
                                          upload_id, storage_upload_id, upload_key, part_size_bytes, part_count,
                                          upload_expires_at, created_by, original_storage_endpoint, original_storage_bucket)
                values (?, ?, 'UPLOADING', ?, ?, ?, ?, ?, ?, ?, ?, ?, now() + make_interval(secs => ?), ?, ?, ?)
                """, id, kind, title, filename, contentType, sizeBytes, uploadId, storageUploadId, uploadKey,
                partSizeBytes, partCount, sessionTtlSeconds, createdBy, storageEndpoint, storageBucket);
    }

    public Optional<AssetRow> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from media_assets where id = ?", ASSET, id).stream().findFirst();
    }

    public List<AssetRow> list(String status, String kind, int limit) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from media_assets where deleted_at is null");
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
                select job_type, rendition, status, attempt, last_error, max_attempts, started_at, finished_at
                  from media_jobs
                 where asset_id = ? and source_version = ?
                   and profile_version = (select profile_version from media_assets where id = ?)
                 order by created_at, rendition
                """, (rs, n) -> new JobRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), rs.getInt(6), instant(rs.getObject(7, OffsetDateTime.class)),
                instant(rs.getObject(8, OffsetDateTime.class))), assetId, sourceVersion, assetId);
    }

    public Optional<AssetRow> lock(UUID id) {
        return jdbc.query("select " + COLUMNS + " from media_assets where id = ? for update", ASSET, id)
                .stream().findFirst();
    }

    public boolean updateMetadata(UUID id, int version, String title, String description) {
        return jdbc.update("""
                update media_assets set title = ?, description = ?, metadata_version = metadata_version + 1,
                       updated_at = now()
                 where id = ? and metadata_version = ? and deleted_at is null
                """, title, description, id, version) == 1;
    }

    public boolean softDelete(UUID id, int version) {
        return jdbc.update("""
                update media_assets set deleted_at = now(), metadata_version = metadata_version + 1, updated_at = now()
                 where id = ? and metadata_version = ? and deleted_at is null and status in ('READY', 'FAILED')
                   and original_status not in ('DELETING', 'DELETE_FAILED')
                """, id, version) == 1;
    }

    /** Conservative reference checks, including drafts. Never silently detach live content. */
    public List<String> references(UUID id) {
        List<String> refs = new ArrayList<>(jdbc.queryForList(
                "select 'placement:' || placement_key from media_presentations where asset_id = ? and enabled = true",
                String.class, id));
        String needle = "%" + id + "%";
        refs.addAll(jdbc.queryForList("""
                select 'project:' || id from "Projects" where content ilike ? or image_url ilike ?
                union all select 'blog:' || id from "Blogs" where content ilike ? or image_url ilike ?
                union all select 'life-blog:' || id from life_blogs where content ilike ? or image_url ilike ?
                """, String.class, needle, needle, needle, needle, needle, needle));
        return refs;
    }

    public boolean hasActiveJobs(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from media_jobs where asset_id = ? and status in ('PENDING', 'RUNNING'))",
                Boolean.class, id));
    }

    public Optional<OriginalDeletion> originalDeletion(UUID id) {
        return jdbc.query("""
                select asset_id, source_version, source_key, storage_bucket, storage_endpoint, storage_generation, expected_metadata_version, requested_by
                  from media_original_deletions where asset_id = ?
                """, (rs, n) -> new OriginalDeletion(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8)), id).stream().findFirst();
    }

    /** Caller holds the asset lock; intent and status must commit together before storage deletion. */
    public void beginOriginalDeletion(AssetRow row, String actor) {
        jdbc.update("""
                insert into media_original_deletions
                    (asset_id, source_version, source_key, storage_bucket, storage_endpoint, storage_generation, expected_metadata_version, requested_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.sourceVersion(), row.sourceKey(), row.originalStorageBucket(), row.originalStorageEndpoint(),
                row.originalStorageGeneration(), row.metadataVersion(), actor);
        setOriginalStatus(row.id(), "DELETING");
    }

    /** Only the deletion transaction may bind a legacy row after verifying its persisted upload receipt. */
    public void bindLegacyOriginal(UUID id, String endpoint, String bucket, String generation) {
        int changed = jdbc.update("""
                update media_assets set original_storage_endpoint = ?, original_storage_bucket = ?, original_storage_generation = ?
                 where id = ? and original_status = 'AVAILABLE' and original_storage_endpoint is null
                   and original_storage_bucket is null and original_storage_generation is null
                """, endpoint, bucket, generation, id);
        if (changed != 1) throw new IllegalStateException("Original storage binding changed");
    }

    public void setOriginalStatus(UUID id, String status) {
        jdbc.update("""
                update media_assets set original_status = ?, metadata_version = metadata_version + 1,
                    original_deleted_at = case when ? = 'DELETED' then now() else null end, updated_at = now()
                 where id = ? and original_status <> ?
                """, status, status, id, status);
        if ("DELETED".equals(status))
            jdbc.update("update media_original_deletions set completed_at = now() where asset_id = ?", id);
    }

    public List<String> presentations(UUID assetId) {
        return jdbc.queryForList("select presentation::text from media_presentations where asset_id = ? and enabled = true",
                String.class, assetId);
    }

    public Map<UUID, List<JobRow>> jobsFor(List<AssetRow> rows) {
        Map<UUID, List<JobRow>> result = new HashMap<>();
        if (rows.isEmpty()) return result;
        String slots = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        jdbc.query("""
                select j.asset_id, j.job_type, j.rendition, j.status, j.attempt, j.last_error,
                       j.max_attempts, j.started_at, j.finished_at
                  from media_jobs j join media_assets a on a.id = j.asset_id
                 where j.source_version = a.source_version and j.profile_version = a.profile_version
                   and j.asset_id in (""" + slots + ") order by j.created_at, j.rendition", rs -> {
            UUID id = rs.getObject(1, UUID.class);
            result.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new JobRow(rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getInt(7),
                    instant(rs.getObject(8, OffsetDateTime.class)), instant(rs.getObject(9, OffsetDateTime.class))));
        }, rows.stream().map(AssetRow::id).toArray());
        return result;
    }

    public Map<UUID, List<String>> presentationsFor(List<AssetRow> rows) {
        Map<UUID, List<String>> result = new HashMap<>();
        if (rows.isEmpty()) return result;
        String slots = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        jdbc.query("select asset_id, presentation::text from media_presentations where enabled = true and asset_id in ("
                + slots + ")", rs -> {
            result.computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new ArrayList<>()).add(rs.getString(2));
        }, rows.stream().map(AssetRow::id).toArray());
        return result;
    }

    /**
     * Step 2 of the pipeline, the binding: this upload becomes the asset's next source version and
     * the asset moves to PROCESSING. Guarded by status and upload id, so of two concurrent
     * completions for the same upload exactly one wins.
     */
    public boolean bindSource(UUID id, String uploadId, String etag, long bytes, String profileVersion, String generation) {
        return jdbc.update("""
                update media_assets
                   set status = 'PROCESSING', source_version = source_version + 1, source_key = upload_key,
                       source_etag = ?, source_bytes = ?, profile_version = ?, original_storage_generation = ?, processing_started_at = now(),
                       storage_upload_id = null, failure_code = null, failure_message = null, updated_at = now()
                 where id = ? and status = 'UPLOADING' and upload_id = ? and deleted_at is null
                """, etag, bytes, profileVersion, generation, id, uploadId) == 1;
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
                   and deleted_at is null and original_status = 'AVAILABLE'
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
