package site.yuqi.media.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import site.yuqi.media.model.Asset;
import site.yuqi.media.model.Job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every SQL statement the Media Service runs. Each one is a single atomic step; the multi-step
 * units that must commit together live in {@link JobFlow}.
 */
@Repository
public class MediaRepository {

    private static final String OUTPUT_TYPES = "('VIDEO_RENDITION','VIDEO_MP4','VIDEO_POSTER','IMAGE_VARIANTS')";

    private static final String ASSET_COLUMNS =
            "id, kind, status, content_type, source_version, source_key, profile_version, probe::text as probe";
    private static final String JOB_COLUMNS = "j.id, j.asset_id, j.source_version, j.profile_version, j.job_type, "
            + "j.rendition, j.spec::text as spec, j.status, j.attempt, j.max_attempts, j.lease_owner, "
            + "j.output::text as output";

    private static final RowMapper<Asset> ASSET = (rs, n) -> new Asset(
            rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("status"),
            rs.getString("content_type"), rs.getInt("source_version"), rs.getString("source_key"),
            rs.getString("profile_version"), rs.getString("probe"));
    private static final RowMapper<Job> JOB = (rs, n) -> new Job(
            rs.getObject("id", UUID.class), rs.getObject("asset_id", UUID.class), rs.getInt("source_version"),
            rs.getString("profile_version"), rs.getString("job_type"), rs.getString("rendition"),
            rs.getString("spec"), rs.getString("status"), rs.getInt("attempt"), rs.getInt("max_attempts"),
            rs.getString("lease_owner"), rs.getString("output"));

    private final JdbcTemplate jdbc;

    public MediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── assets ────────────────────────────────────────────────────────────────

    public Optional<Asset> findAsset(UUID id) {
        return jdbc.query("select " + ASSET_COLUMNS + " from media_assets where id = ?", ASSET, id)
                .stream().findFirst();
    }

    /** Takes the asset's row lock for the rest of the transaction, so its jobs change one at a time. */
    public Optional<Asset> lockAsset(UUID id) {
        return jdbc.query("select " + ASSET_COLUMNS + " from media_assets where id = ? for update", ASSET, id)
                .stream().findFirst();
    }

    public String assetStatus(UUID id) {
        return jdbc.query("select status from media_assets where id = ?", (rs, n) -> rs.getString(1), id)
                .stream().findFirst().orElse(null);
    }

    public void saveProbe(UUID assetId, int sourceVersion, String probeJson) {
        jdbc.update("update media_assets set probe = ?::jsonb, updated_at = now() "
                + "where id = ? and source_version = ?", probeJson, assetId, sourceVersion);
    }

    /**
     * The publish pointer. It moves only if the asset is still processing the same source version;
     * a job that finishes for a source that has since been replaced changes nothing.
     */
    public boolean publish(UUID assetId, int sourceVersion, String profileVersion, String manifestJson) {
        return jdbc.update("""
                update media_assets
                   set status = 'READY', manifest = ?::jsonb, published_source_version = ?,
                       published_profile_version = ?, published_at = now(),
                       failure_code = null, failure_message = null, updated_at = now()
                 where id = ? and source_version = ? and status = 'PROCESSING'
                """, manifestJson, sourceVersion, profileVersion, assetId, sourceVersion) == 1;
    }

    public boolean failAsset(UUID assetId, int sourceVersion, String code, String message) {
        return jdbc.update("""
                update media_assets
                   set status = 'FAILED', failure_code = ?, failure_message = ?, updated_at = now()
                 where id = ? and source_version = ? and status = 'PROCESSING'
                """, code, truncate(message, 2000), assetId, sourceVersion) == 1;
    }

    // ── jobs ──────────────────────────────────────────────────────────────────

    /** False when the job already exists: the unique key is what makes a replayed message harmless. */
    public boolean insertJob(UUID assetId, int sourceVersion, String profileVersion, String jobType,
                             String rendition, String specJson, int maxAttempts) {
        return jdbc.update("""
                insert into media_jobs (id, asset_id, source_version, profile_version, job_type, rendition,
                                        spec, max_attempts)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                on conflict (asset_id, source_version, profile_version, rendition) do nothing
                """, UUID.randomUUID(), assetId, sourceVersion, profileVersion, jobType, rendition,
                specJson, maxAttempts) == 1;
    }

    /**
     * Leases the oldest runnable job to {@code owner}. SKIP LOCKED lets any number of workers ask
     * at once without waiting on each other, and the attempt number goes up with every lease.
     */
    public Optional<Job> claimNext(String owner, int leaseSeconds) {
        return jdbc.query("""
                with next as (
                    select id from media_jobs
                     where status = 'PENDING' and next_attempt_at <= now()
                     order by next_attempt_at, created_at
                     limit 1
                       for update skip locked
                )
                update media_jobs j
                   set status = 'RUNNING', attempt = j.attempt + 1, lease_owner = ?,
                       lease_expires_at = now() + make_interval(secs => ?),
                       started_at = now(), finished_at = null, updated_at = now()
                  from next
                 where j.id = next.id
                returning\s""" + JOB_COLUMNS, JOB, owner, leaseSeconds).stream().findFirst();
    }

    /** False means the lease is gone: someone else owns the job now and this worker must stop. */
    public boolean heartbeat(Job job, String owner, int leaseSeconds) {
        return jdbc.update("""
                update media_jobs
                   set lease_expires_at = now() + make_interval(secs => ?), updated_at = now()
                 where id = ? and status = 'RUNNING' and lease_owner = ? and attempt = ?
                """, leaseSeconds, job.id(), owner, job.attempt()) == 1;
    }

    /** Accepts a result only from the attempt that still holds the lease. */
    public boolean complete(Job job, String owner, String outputJson) {
        return jdbc.update("""
                update media_jobs
                   set status = 'SUCCEEDED', output = ?::jsonb, last_error = null, lease_expires_at = null,
                       finished_at = now(), updated_at = now()
                 where id = ? and status = 'RUNNING' and lease_owner = ? and attempt = ?
                """, outputJson, job.id(), owner, job.attempt()) == 1;
    }

    /**
     * Gives the job back for another try after {@code backoffSeconds}, or ends it as DEAD when the
     * error is permanent or the attempts are used up. Empty when the lease was already lost.
     */
    public Optional<String> fail(Job job, String owner, String error, boolean permanent, long backoffSeconds) {
        return jdbc.query("""
                update media_jobs
                   set status = case when ? or attempt >= max_attempts then 'DEAD' else 'PENDING' end,
                       finished_at = case when ? or attempt >= max_attempts then now() else null end,
                       next_attempt_at = now() + make_interval(secs => ?),
                       last_error = ?, lease_owner = null, lease_expires_at = null, updated_at = now()
                 where id = ? and status = 'RUNNING' and lease_owner = ? and attempt = ?
                returning status
                """, (rs, n) -> rs.getString(1), permanent, permanent, backoffSeconds, truncate(error, 4000),
                job.id(), owner, job.attempt()).stream().findFirst();
    }

    /** Takes back every job whose worker stopped renewing its lease. Returns them in their new state. */
    public List<Job> reapExpiredLeases() {
        return jdbc.query("""
                update media_jobs j
                   set status = case when attempt >= max_attempts then 'DEAD' else 'PENDING' end,
                       finished_at = case when attempt >= max_attempts then now() else null end,
                       last_error = 'lease expired during attempt ' || attempt,
                       lease_owner = null, lease_expires_at = null, next_attempt_at = now(), updated_at = now()
                 where status = 'RUNNING' and lease_expires_at < now()
                returning\s""" + JOB_COLUMNS, JOB);
    }

    public List<Job> jobsFor(UUID assetId, int sourceVersion, String profileVersion) {
        return jdbc.query("select " + JOB_COLUMNS + " from media_jobs j where j.asset_id = ? "
                        + "and j.source_version = ? and j.profile_version = ? order by j.created_at, j.rendition",
                JOB, assetId, sourceVersion, profileVersion);
    }

    /** True when at least one output job exists and none is still open. */
    public boolean allOutputsSucceeded(UUID assetId, int sourceVersion, String profileVersion) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*) > 0 and count(*) filter (where status <> 'SUCCEEDED') = 0
                  from media_jobs
                 where asset_id = ? and source_version = ? and profile_version = ?
                   and job_type in\s""" + OUTPUT_TYPES, Boolean.class, assetId, sourceVersion, profileVersion));
    }

    public int cancelPending(UUID assetId, int sourceVersion, String profileVersion, String reason) {
        return jdbc.update("""
                update media_jobs
                   set status = 'DEAD', last_error = ?, finished_at = now(), updated_at = now()
                 where asset_id = ? and source_version = ? and profile_version = ? and status = 'PENDING'
                """, reason, assetId, sourceVersion, profileVersion);
    }

    public Optional<String> firstError(UUID assetId, int sourceVersion, String profileVersion) {
        return jdbc.query("""
                select last_error from media_jobs
                 where asset_id = ? and source_version = ? and profile_version = ?
                   and status = 'DEAD' and last_error is not null and last_error not like 'cancelled:%'
                 order by finished_at limit 1
                """, (rs, n) -> rs.getString(1), assetId, sourceVersion, profileVersion).stream().findFirst();
    }

    // ── recovery sweeps ───────────────────────────────────────────────────────

    /** Uploads that were bound a while ago but never got a PLAN job: the message was lost or expired. */
    public List<Asset> findUnplanned(int olderThanSeconds) {
        return jdbc.query("select " + ASSET_COLUMNS + """
                  from media_assets a
                 where a.status = 'PROCESSING' and a.source_version > 0 and a.profile_version is not null
                   and a.processing_started_at < now() - make_interval(secs => ?)
                   and not exists (select 1 from media_jobs j
                                    where j.asset_id = a.id and j.source_version = a.source_version
                                      and j.profile_version = a.profile_version and j.job_type = 'PLAN')
                 order by a.processing_started_at limit 50
                """, ASSET, olderThanSeconds);
    }

    /** Assets whose outputs are all done but nobody queued the PUBLISH job. */
    public List<Asset> findPublishable() {
        return jdbc.query(("select " + ASSET_COLUMNS + """
                  from media_assets a
                 where a.status = 'PROCESSING'
                   and exists (select 1 from media_jobs j
                                where j.asset_id = a.id and j.source_version = a.source_version
                                  and j.profile_version = a.profile_version and j.job_type in {outputs})
                   and not exists (select 1 from media_jobs j
                                    where j.asset_id = a.id and j.source_version = a.source_version
                                      and j.profile_version = a.profile_version
                                      and j.job_type in {outputs} and j.status <> 'SUCCEEDED')
                   and not exists (select 1 from media_jobs j
                                    where j.asset_id = a.id and j.source_version = a.source_version
                                      and j.profile_version = a.profile_version and j.job_type = 'PUBLISH')
                 limit 50
                """).replace("{outputs}", OUTPUT_TYPES), ASSET);
    }

    /** Assets still marked PROCESSING although one of their jobs is DEAD. */
    public List<Asset> findProcessingWithDeadJob() {
        return jdbc.query("select " + ASSET_COLUMNS + """
                  from media_assets a
                 where a.status = 'PROCESSING'
                   and exists (select 1 from media_jobs j
                                where j.asset_id = a.id and j.source_version = a.source_version
                                  and j.profile_version = a.profile_version and j.status = 'DEAD')
                 limit 50
                """, ASSET);
    }

    // ── poison Kafka records ──────────────────────────────────────────────────

    public void quarantine(String topic, int partition, long offset, String payload, String errorType) {
        jdbc.update("""
                insert into indexing_kafka_quarantine (worker, topic, partition_id, record_offset, payload, error_type)
                values ('MEDIA', ?, ?, ?, ?, ?) on conflict do nothing
                """, topic, partition, offset, payload, truncate(errorType, 255));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
