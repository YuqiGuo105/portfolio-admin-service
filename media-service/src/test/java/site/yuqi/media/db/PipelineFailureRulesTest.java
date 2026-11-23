package site.yuqi.media.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import site.yuqi.media.intake.WorkEvent;
import site.yuqi.media.intake.WorkIntake;
import site.yuqi.media.model.Asset;
import site.yuqi.media.model.Job;
import site.yuqi.media.ops.Recovery;
import site.yuqi.media.support.TestDatabase;
import site.yuqi.media.support.TestProperties;
import site.yuqi.media.work.JobRunner;
import site.yuqi.media.work.WorkDirectories;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The failure rules of the upload → transcode → publish design, one test each, against a real
 * PostgreSQL. No FFmpeg here: these are about what the database accepts and refuses.
 */
@EnabledIfEnvironmentVariable(named = TestDatabase.ENV, matches = TestDatabase.LOCAL_ONLY)
class PipelineFailureRulesTest {

    private TestDatabase db;
    private MediaRepository repository;
    private JobFlow flow;
    private JobRunner runner;

    @BeforeEach
    void setUp() {
        db = new TestDatabase();
        repository = new MediaRepository(db.jdbc);
        flow = new JobFlow(repository, db.transactions);
        var properties = TestProperties.defaults();
        runner = new JobRunner(repository, flow, new WorkDirectories(properties), properties, List.of());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    // Rule 2 and 3: a replayed Kafka message must not create a second set of work.
    @Test
    void aReplayedMessageCreatesTheWorkOnlyOnce() {
        UUID asset = db.processingAsset(Asset.VIDEO);
        WorkIntake intake = new WorkIntake(repository, flow, runner);
        WorkEvent event = event(asset, 1);

        assertThat(intake.accept(event)).isEqualTo(WorkIntake.Outcome.ACCEPTED);
        assertThat(intake.accept(event)).isEqualTo(WorkIntake.Outcome.DUPLICATE);
        assertThat(db.count("select count(*) from media_jobs where asset_id = ?", asset)).isEqualTo(1);
    }

    @Test
    void intakeIgnoresIndexingEventsAndRefusesBrokenOnes() {
        UUID asset = db.processingAsset(Asset.VIDEO);
        WorkIntake intake = new WorkIntake(repository, flow, runner);

        WorkEvent indexing = new WorkEvent("e1", Instant.now(), null, null, null, 1, "BLOG", "42", 3,
                UUID.randomUUID().toString(), "k", "SEARCH_INDEX", null, null);
        assertThat(intake.accept(indexing)).isEqualTo(WorkIntake.Outcome.IGNORED);

        assertThat(intake.accept(event(asset, 7))).isEqualTo(WorkIntake.Outcome.STALE);
        assertThatThrownBy(() -> intake.accept(event(UUID.randomUUID(), 1)))
                .isInstanceOf(WorkIntake.PoisonEventException.class).hasMessageContaining("unknown asset");
        WorkEvent wrongKey = new WorkEvent("e2", Instant.now(), null, null, null, 1, "MEDIA", asset.toString(), 1,
                null, "k", "MEDIA_PROCESS", "originals/somewhere/else.mp4", "v1");
        assertThatThrownBy(() -> intake.accept(wrongKey)).isInstanceOf(WorkIntake.PoisonEventException.class);
        assertThat(db.count("select count(*) from media_jobs")).isZero();
    }

    // Rule 4: a worker dies mid-transcode. Finished renditions stay, only the unfinished one is retried.
    @Test
    void aDeadWorkerLosesItsLeaseAndOnlyItsJobIsRetried() {
        UUID asset = db.processingAsset(Asset.VIDEO);
        Map<String, Job> leased = leaseAll(asset, "360p", "720p", "1080p");
        flow.completeOutput(leased.get("360p"), "w-360p", "{}");
        flow.completeOutput(leased.get("1080p"), "w-1080p", "{}");

        expireLease(leased.get("720p"));
        new Recovery(repository, flow, runner, TestProperties.defaults()).reapExpiredLeases();

        Job retried = repository.claimNext("rescuer", 120).orElseThrow();
        assertThat(retried.rendition()).isEqualTo("720p");
        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(repository.claimNext("rescuer", 120)).isEmpty();
        assertThat(statuses(asset)).containsEntry("360p", "SUCCEEDED").containsEntry("1080p", "SUCCEEDED");
        assertThat(db.assetStatus(asset)).isEqualTo("PROCESSING");
    }

    // Rule 5: the old worker was only slow. Its late result must not replace the new attempt's.
    @Test
    void aStaleWorkerCannotOverwriteTheAttemptThatReplacedIt() {
        UUID asset = db.processingAsset(Asset.VIDEO);
        Job first = leaseAll(asset, "720p").get("720p");
        expireLease(first);
        repository.reapExpiredLeases();
        Job second = repository.claimNext("worker-b", 120).orElseThrow();

        assertThat(second.attempt()).isEqualTo(first.attempt() + 1);
        assertThat(second.outputPrefix()).isNotEqualTo(first.outputPrefix()).endsWith("/a2/720p");
        assertThat(repository.heartbeat(first, "w-720p", 120)).isFalse();

        assertThat(flow.completeOutput(second, "worker-b", "{\"by\":\"b\"}")).isEqualTo(JobFlow.Completion.ACCEPTED);
        assertThat(flow.completeOutput(first, "w-720p", "{\"by\":\"a\"}")).isEqualTo(JobFlow.Completion.LEASE_LOST);
        assertThat(flow.fail(first, "w-720p", "late failure", false, 0)).isEqualTo(JobFlow.Failure.LEASE_LOST);
        assertThat(db.jdbc.queryForObject("select output->>'by' from media_jobs where id = ?", String.class, first.id()))
                .isEqualTo("b");
    }

    // Rule 4: passing trouble is retried after a delay; a broken input ends the job at once.
    @Test
    void passingErrorsAreRetriedLaterAndPermanentOnesEndTheAsset() {
        UUID asset = db.processingAsset(Asset.VIDEO);
        Map<String, Job> leased = leaseAll(asset, "360p");
        repository.insertJob(asset, 1, "v1", Job.VIDEO_RENDITION, "720p", "{}", 3);

        assertThat(flow.fail(leased.get("360p"), "w-360p", "storage timeout", false, 60)).isEqualTo(JobFlow.Failure.RETRY);
        Job other = repository.claimNext("w", 120).orElseThrow();
        assertThat(other.rendition()).isEqualTo("720p");
        assertThat(repository.claimNext("w", 120)).as("360p is backing off").isEmpty();
        assertThat(db.assetStatus(asset)).isEqualTo("PROCESSING");

        assertThat(flow.fail(other, "w", "the file has no video stream", true, 0)).isEqualTo(JobFlow.Failure.DEAD);
        assertThat(db.assetStatus(asset)).isEqualTo("FAILED");
        assertThat(db.jdbc.queryForObject("select failure_code || ': ' || failure_message from media_assets where id = ?",
                String.class, asset)).isEqualTo("unsupported_input: the file has no video stream");
        assertThat(statuses(asset)).containsEntry("360p", "DEAD").containsEntry("720p", "DEAD");
    }

    @Test
    void theLastAllowedAttemptEndsTheAsset() {
        UUID asset = db.processingAsset(Asset.VIDEO);
        repository.insertJob(asset, 1, "v1", Job.VIDEO_RENDITION, "720p", "{}", 2);

        Job first = repository.claimNext("w", 120).orElseThrow();
        assertThat(flow.fail(first, "w", "boom", false, 0)).isEqualTo(JobFlow.Failure.RETRY);
        Job second = repository.claimNext("w", 120).orElseThrow();
        assertThat(flow.fail(second, "w", "boom again", false, 0)).isEqualTo(JobFlow.Failure.DEAD);

        assertThat(db.assetStatus(asset)).isEqualTo("FAILED");
        assertThat(repository.claimNext("w", 120)).isEmpty();
    }

    // Rule 6: nothing is published until every output is in, and then exactly once.
    @Test
    void publishIsQueuedOnceWhenTheLastOutputFinishes() {
        UUID asset = db.processingAsset(Asset.VIDEO);
        Map<String, Job> leased = leaseAll(asset, "360p", "720p");

        flow.completeOutput(leased.get("360p"), "w-360p", "{}");
        assertThat(db.count("select count(*) from media_jobs where asset_id = ? and job_type = 'PUBLISH'", asset)).isZero();

        flow.completeOutput(leased.get("720p"), "w-720p", "{}");
        assertThat(db.count("select count(*) from media_jobs where asset_id = ? and job_type = 'PUBLISH'", asset)).isEqualTo(1);
        assertThat(flow.queuePublishIfReady(asset, 1, "v1")).as("already queued").isFalse();
        assertThat(db.assetStatus(asset)).as("queued is not published").isEqualTo("PROCESSING");
    }

    @Test
    void twoOutputsFinishingTogetherStillQueueExactlyOnePublish() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                UUID asset = db.processingAsset(Asset.VIDEO);
                Map<String, Job> leased = leaseAll(asset, "360p", "720p");
                CountDownLatch start = new CountDownLatch(1);
                var a = pool.submit(() -> { start.await(); return flow.completeOutput(leased.get("360p"), "w-360p", "{}"); });
                var b = pool.submit(() -> { start.await(); return flow.completeOutput(leased.get("720p"), "w-720p", "{}"); });
                start.countDown();
                assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(JobFlow.Completion.ACCEPTED);
                assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(JobFlow.Completion.ACCEPTED);
                assertThat(db.count("select count(*) from media_jobs where asset_id = ? and job_type = 'PUBLISH'", asset))
                        .as("round " + round).isEqualTo(1);
                // Take the queued PUBLISH off the queue so the next round leases its own jobs.
                assertThat(repository.claimNext("publisher", 120).orElseThrow().jobType()).isEqualTo(Job.PUBLISH);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // Rule 6: the pointer moves last, and only if the asset still has the source this work was for.
    @Test
    void publishingMovesThePointerOnlyForTheCurrentSource() {
        UUID current = db.processingAsset(Asset.VIDEO);
        repository.insertJob(current, 1, "v1", Job.PUBLISH, "publish", "{}", 3);
        Job publish = repository.claimNext("p", 120).orElseThrow();
        assertThat(flow.publish(publish, "p", "{\"hls\":\"master-abc.m3u8\"}")).isTrue();
        assertThat(db.jdbc.queryForObject("select status || ' ' || (manifest->>'hls') || ' v' || published_source_version "
                + "from media_assets where id = ?", String.class, current)).isEqualTo("READY master-abc.m3u8 v1");
        assertThat(db.count("select count(*) from media_public where id = ?", current)).isEqualTo(1);

        UUID replaced = db.processingAsset(Asset.VIDEO);
        repository.insertJob(replaced, 1, "v1", Job.PUBLISH, "publish", "{}", 3);
        Job stale = repository.claimNext("p", 120).orElseThrow();
        db.jdbc.update("update media_assets set source_version = 2 where id = ?", replaced);
        assertThat(flow.publish(stale, "p", "{\"hls\":\"old.m3u8\"}")).isFalse();
        assertThat(db.jdbc.queryForObject("select status || ' ' || coalesce(manifest::text, 'no manifest') "
                + "from media_assets where id = ?", String.class, replaced)).isEqualTo("PROCESSING no manifest");
        assertThat(db.count("select count(*) from media_public where id = ?", replaced)).isZero();
    }

    @Test
    void workersNeverLeaseTheSameJobTwice() throws Exception {
        UUID asset = db.processingAsset(Asset.VIDEO);
        for (int i = 0; i < 40; i++) repository.insertJob(asset, 1, "v1", Job.VIDEO_RENDITION, "r" + i, "{}", 3);

        Set<UUID> leased = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Integer>> workers = new java.util.ArrayList<>();
            for (int w = 0; w < 8; w++) {
                String owner = "worker-" + w;
                workers.add(pool.submit(() -> {
                    start.await();
                    int mine = 0;
                    for (Optional<Job> job = repository.claimNext(owner, 120); job.isPresent();
                         job = repository.claimNext(owner, 120)) {
                        assertThat(leased.add(job.get().id())).as("leased twice: " + job.get().rendition()).isTrue();
                        mine++;
                    }
                    return mine;
                }));
            }
            start.countDown();
            int total = 0;
            for (var worker : workers) total += worker.get(20, TimeUnit.SECONDS);
            assertThat(total).isEqualTo(40);
            assertThat(leased).hasSize(40);
        } finally {
            pool.shutdownNow();
        }
    }

    // Rule 6, last paragraph: a periodic task repairs what a lost message or a crash left behind.
    @Test
    void theRecoverySweepRepairsWhatFellThroughAGap() {
        var properties = TestProperties.with(Map.of("portfolio.media-pipeline.recovery.unplanned-after-seconds", "30"));
        Recovery recovery = new Recovery(repository, flow, runner, properties);

        UUID lostMessage = db.processingAsset(Asset.VIDEO);
        db.jdbc.update("update media_assets set processing_started_at = now() - interval '5 minutes' where id = ?", lostMessage);
        UUID justUploaded = db.processingAsset(Asset.VIDEO);

        UUID crashedBeforePublish = db.processingAsset(Asset.VIDEO);
        Job only = leaseAll(crashedBeforePublish, "720p").get("720p");
        repository.complete(only, "w-720p", "{}");

        UUID crashedAfterDeadJob = db.processingAsset(Asset.VIDEO);
        Job doomed = leaseAll(crashedAfterDeadJob, "720p").get("720p");
        repository.fail(doomed, "w-720p", "the file is corrupt", true, 0);

        recovery.reconcile();

        assertThat(statuses(lostMessage)).containsEntry("plan", "PENDING");
        assertThat(statuses(justUploaded)).as("too recent to call lost").isEmpty();
        assertThat(statuses(crashedBeforePublish)).containsEntry("publish", "PENDING");
        assertThat(db.assetStatus(crashedAfterDeadJob)).isEqualTo("FAILED");
        assertThat(db.jdbc.queryForObject("select failure_message from media_assets where id = ?", String.class,
                crashedAfterDeadJob)).isEqualTo("the file is corrupt");

        recovery.reconcile();
        assertThat(db.count("select count(*) from media_jobs where asset_id = ?", lostMessage)).as("sweeps are idempotent").isEqualTo(1);
    }

    @Test
    void aPoisonRecordIsKeptForAHumanAndStoredOnce() {
        repository.quarantine("content.search.index.v1", 1, 42L, "{\"broken\":true}", "PoisonEventException");
        repository.quarantine("content.search.index.v1", 1, 42L, "{\"broken\":true}", "PoisonEventException");
        assertThat(db.count("select count(*) from indexing_kafka_quarantine where worker = 'MEDIA'")).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static WorkEvent event(UUID asset, int sourceVersion) {
        return new WorkEvent(UUID.randomUUID().toString(), Instant.now(), "trace", "corr", null, 1, "MEDIA",
                asset.toString(), sourceVersion, null, "media:" + asset + ":" + sourceVersion + ":v1",
                "MEDIA_PROCESS", null, "v1");
    }

    /** Creates one rendition job per name and leases each to a worker called {@code w-<name>}. */
    private Map<String, Job> leaseAll(UUID asset, String... renditions) {
        Map<String, Job> leased = new HashMap<>();
        for (String rendition : renditions) {
            repository.insertJob(asset, 1, "v1", Job.VIDEO_RENDITION, rendition, "{}", 3);
            Job job = repository.claimNext("w-" + rendition, 120).orElseThrow();
            assertThat(job.rendition()).isEqualTo(rendition);
            leased.put(rendition, job);
        }
        return leased;
    }

    private void expireLease(Job job) {
        db.jdbc.update("update media_jobs set lease_expires_at = now() - interval '1 second' where id = ?", job.id());
    }

    private Map<String, String> statuses(UUID asset) {
        Map<String, String> statuses = new HashMap<>();
        db.jdbc.query("select rendition, status from media_jobs where asset_id = ?",
                rs -> { statuses.put(rs.getString(1), rs.getString(2)); }, asset);
        return statuses;
    }
}
