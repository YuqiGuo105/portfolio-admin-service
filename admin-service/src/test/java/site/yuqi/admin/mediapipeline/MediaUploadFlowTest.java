package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import site.yuqi.admin.events.CloudRunIdentityTokenProvider;
import site.yuqi.admin.mediapipeline.MediaUploadService.AssetView;
import site.yuqi.admin.mediapipeline.MediaUploadService.StartUpload;
import site.yuqi.admin.mediapipeline.MediaUploadService.UploadSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Steps 1 to 3 against a real PostgreSQL and an in-memory S3: the browser's part of the upload,
 * the binding of the source, and the outbox that guarantees the event is eventually sent.
 */
@EnabledIfEnvironmentVariable(named = MediaTestSchema.ENV, matches = MediaTestSchema.LOCAL_ONLY)
class MediaUploadFlowTest {

    private static final int PART = 5 * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient browser = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final List<MediaWorkEvent> delivered = new CopyOnWriteArrayList<>();
    private volatile boolean brokerDown;
    private MediaTestSchema db;
    private FakeS3 s3;
    private MediaAssetStore assets;
    private MediaOutboxRelay relay;
    private MediaUploadService uploads;

    @BeforeEach
    void setUp() throws Exception {
        db = new MediaTestSchema();
        s3 = new FakeS3();
        build(Map.of());
    }

    private void build(Map<String, String> overrides) {
        if (relay != null) relay.close();
        Map<String, String> settings = new HashMap<>(Map.of(
                "portfolio.media-pipeline.enabled", "true",
                "portfolio.media-pipeline.storage.endpoint", s3.endpoint(),
                "portfolio.media-pipeline.storage.access-key", "test",
                "portfolio.media-pipeline.storage.secret-key", "test",
                "portfolio.media-pipeline.upload.part-size-bytes", Integer.toString(PART)));
        settings.putAll(overrides);
        MediaPipelineProperties properties = new Binder(new MapConfigurationPropertySource(settings))
                .bindOrCreate("portfolio.media-pipeline", MediaPipelineProperties.class);
        assets = new MediaAssetStore(db.jdbc);
        MediaEventTransport transport = event -> {
            if (brokerDown) throw new IllegalStateException("broker unavailable");
            delivered.add(event);
        };
        MediaServiceWakeClient wake = new MediaServiceWakeClient(properties,
                new CloudRunIdentityTokenProvider(mapper, "http://127.0.0.1:9/unused"));
        relay = new MediaOutboxRelay(assets, transport, mapper, properties);
        uploads = new MediaUploadService(assets, new OriginalsStore(properties), properties, relay,
                db.transactions, mapper);
    }

    @AfterEach
    void tearDown() {
        relay.close();
        s3.close();
        db.close();
    }

    @Test
    void theBrowserUploadsToTheBucketAndCompletionBindsTheSourceAndOwesOneEvent() throws Exception {
        brokerDown = true;   // the event must exist in the database whether or not it can be sent
        byte[] file = bytes(PART + 1234);
        UploadSession session = uploads.start(
                new StartUpload(null, "C:\\clips\\My Demo (final).mp4", "video/mp4", file.length, "Demo"), "admin@example.com");

        assertThat(session.partCount()).isEqualTo(2);
        assertThat(session.parts().get(0).url()).startsWith(s3.endpoint() + "/portfolio-media-originals/originals/"
                + session.assetId() + "/" + session.uploadId() + "/My_Demo__final_.mp4?").contains("X-Amz-Signature=");
        assertThat(db.text("select status from media_assets where id = ?", session.assetId())).isEqualTo("UPLOADING");

        put(session, 1, Arrays.copyOfRange(file, 0, PART));
        put(session, 2, Arrays.copyOfRange(file, PART, file.length));
        AssetView view = uploads.complete(session.assetId(), session.uploadId());

        assertThat(view.status()).isEqualTo("PROCESSING");
        assertThat(view.sourceVersion()).isEqualTo(1);
        String key = db.text("select source_key from media_assets where id = ?", session.assetId());
        assertThat(s3.objects.get("/portfolio-media-originals/" + key)).isEqualTo(file);
        assertThat(db.text("select created_by || ' ' || profile_version || ' ' || source_bytes from media_assets where id = ?",
                session.assetId())).isEqualTo("admin@example.com v1 " + file.length);

        assertThat(db.count("select count(*) from media_event_outbox where asset_id = ?", session.assetId())).isEqualTo(1);
        JsonNode payload = mapper.readTree(db.text("select payload::text from media_event_outbox where asset_id = ?", session.assetId()));
        assertThat(payload.path("jobType").asText()).isEqualTo("MEDIA_PROCESS");
        assertThat(payload.path("sourceType").asText()).isEqualTo("MEDIA");
        assertThat(payload.path("sourceId").asText()).isEqualTo(session.assetId().toString());
        assertThat(payload.path("sourceVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("sourceKey").asText()).isEqualTo(key);
        assertThat(payload.path("profileVersion").asText()).isEqualTo("v1");
        assertThat(payload.has("indexingJobId")).as("the indexer tells media events apart by this").isFalse();
    }

    @Test
    void completingAgainOrFromTwoRequestsAtOnceOwesExactlyOneEvent() throws Exception {
        UploadSession repeated = startAndUpload(100);
        uploads.complete(repeated.assetId(), repeated.uploadId());
        AssetView again = uploads.complete(repeated.assetId(), repeated.uploadId());
        assertThat(again.status()).isEqualTo("PROCESSING");
        assertThat(again.sourceVersion()).isEqualTo(1);
        assertThat(db.count("select count(*) from media_event_outbox where asset_id = ?", repeated.assetId())).isEqualTo(1);

        UploadSession raced = startAndUpload(100);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<AssetView>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(pool.submit(() -> { go.await(); return uploads.complete(raced.assetId(), raced.uploadId()); }));
            }
            go.countDown();
            for (Future<AssetView> call : calls) assertThat(call.get(20, TimeUnit.SECONDS).sourceVersion()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.count("select count(*) from media_event_outbox where asset_id = ?", raced.assetId())).isEqualTo(1);
        assertThatThrownBy(() -> uploads.complete(raced.assetId(), "another-session")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anIncompleteUploadIsRefusedAndCanBeResumed() throws Exception {
        byte[] file = bytes(PART + 10);
        UploadSession session = uploads.start(new StartUpload("VIDEO", "clip.mp4", "video/mp4", file.length, null), "admin");
        put(session, 1, Arrays.copyOfRange(file, 0, PART));

        assertThatThrownBy(() -> uploads.complete(session.assetId(), session.uploadId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("1 of 2 parts");
        assertThat(db.text("select status from media_assets where id = ?", session.assetId())).isEqualTo("UPLOADING");
        assertThat(db.count("select count(*) from media_event_outbox")).isZero();

        UploadSession resumed = uploads.resume(session.assetId(), session.uploadId());
        assertThat(resumed.uploadedParts()).containsExactly(1);
        put(resumed, 2, Arrays.copyOfRange(file, PART, file.length));
        assertThat(uploads.complete(session.assetId(), session.uploadId()).status()).isEqualTo("PROCESSING");
    }

    // Rule 1: the database was updated but the message could not be sent.
    @Test
    void theOwedEventIsSentOnceTheBrokerIsBackAndOnlyOnce() throws Exception {
        brokerDown = true;
        UploadSession session = startAndUpload(100);
        uploads.complete(session.assetId(), session.uploadId());
        relay.drainOnce();
        awaitAttempts(session.assetId(), 1);

        assertThat(delivered).isEmpty();
        assertThat(db.text("select status from media_event_outbox where asset_id = ?", session.assetId())).isEqualTo("PENDING");
        assertThat(db.text("select last_error from media_event_outbox where asset_id = ?", session.assetId())).contains("broker unavailable");
        assertThat(db.count("select count(*) from media_event_outbox where next_attempt_at > now()")).as("backing off").isEqualTo(1);

        brokerDown = false;
        relay.close(); // Stop background claims before manually advancing the retry clock.
        makeDue();
        assertThat(relay.drainOnce()).isEqualTo(1);
        assertThat(relay.drainOnce()).isZero();

        assertThat(delivered).hasSize(1);
        MediaWorkEvent event = delivered.get(0);
        assertThat(event.partitionKey()).isEqualTo("MEDIA:" + session.assetId());
        assertThat(event.jobType()).isEqualTo("MEDIA_PROCESS");
        assertThat(event.idempotencyKey()).isEqualTo("media:" + session.assetId() + ":1:v1");
        assertThat(event.occurredAt()).isNotNull();
        assertThat(db.text("select status from media_event_outbox where asset_id = ?", session.assetId())).isEqualTo("SENT");
    }

    @Test
    void aRelayThatDiedMidDeliveryIsReplacedWhenItsLeaseRunsOut() throws Exception {
        brokerDown = true;
        UploadSession session = startAndUpload(100);
        uploads.complete(session.assetId(), session.uploadId());
        awaitAttempts(session.assetId(), 1);
        relay.close();
        makeDue();
        assertThat(assets.claimOutbox(10, 120)).as("a relay takes the event and then dies").hasSize(1);

        brokerDown = false;
        assertThat(relay.drainOnce()).as("still leased to the dead relay").isZero();
        db.jdbc.update("update media_event_outbox set lease_expires_at = now() - interval '1 second'");
        assertThat(relay.drainOnce()).isEqualTo(1);
        assertThat(delivered).hasSize(1);
    }

    @Test
    void anEventThatNeverGetsThroughIsParkedForAHumanNotDropped() throws Exception {
        build(Map.of("portfolio.media-pipeline.events.max-attempts", "2"));
        brokerDown = true;
        UploadSession session = startAndUpload(100);
        uploads.complete(session.assetId(), session.uploadId());
        awaitAttempts(session.assetId(), 1);
        relay.close();
        makeDue();
        relay.drainOnce();
        awaitAttempts(session.assetId(), 2);

        assertThat(db.text("select status || ' ' || attempts from media_event_outbox where asset_id = ?", session.assetId()))
                .isEqualTo("DLQ 2");
        makeDue();
        assertThat(relay.drainOnce()).isZero();
    }

    @Test
    void uploadsThatCannotBeProcessedAreRefusedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> uploads.start(new StartUpload(null, "tool.exe", "application/octet-stream", 10, null), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not accepted");
        assertThatThrownBy(() -> uploads.start(new StartUpload(null, "huge.mp4", "video/mp4", 3L * 1024 * 1024 * 1024, null), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sizeBytes");
        assertThatThrownBy(() -> uploads.start(new StartUpload("IMAGE", "clip.mp4", "video/mp4", 10, null), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
        assertThat(db.count("select count(*) from media_assets")).isZero();
        assertThat(s3.hasOpenUpload()).isFalse();

        assertThat(MediaUploadService.safeName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(MediaUploadService.safeName("..hidden")).isEqualTo("hidden");
        assertThat(MediaUploadService.safeName("演示 视频.MOV")).isEqualTo("_____.MOV");
        assertThat(MediaUploadService.safeName(null)).isEqualTo("upload");
    }

    @Test
    void aFailedAssetCanBeRetriedWithTheSourceItAlreadyHas() throws Exception {
        UploadSession session = startAndUpload(100);
        uploads.complete(session.assetId(), session.uploadId());
        assertThatThrownBy(() -> uploads.retry(session.assetId())).isInstanceOf(IllegalStateException.class);

        db.jdbc.update("update media_assets set status = 'FAILED', failure_code = 'processing_failed', failure_message = 'x' where id = ?", session.assetId());
        db.jdbc.update("insert into media_jobs (id, asset_id, source_version, profile_version, job_type, rendition, status, attempt, last_error) "
                + "values (?, ?, 1, 'v1', 'VIDEO_RENDITION', '720p', 'DEAD', 3, 'boom')", UUID.randomUUID(), session.assetId());

        AssetView retried = uploads.retry(session.assetId());

        assertThat(retried.status()).isEqualTo("PROCESSING");
        assertThat(retried.failureCode()).isNull();
        assertThat(retried.jobs()).singleElement().satisfies(job -> {
            assertThat(job.status()).isEqualTo("PENDING");
            assertThat(job.attempt()).isZero();
        });
        assertThat(db.count("select count(*) from media_event_outbox where asset_id = ?", session.assetId())).isEqualTo(2);
        assertThat(db.text("select source_version::text from media_assets where id = ?", session.assetId())).isEqualTo("1");
    }

    @Test
    void anAbandonedUploadIsAbortedInTheBucketAndClosed() throws Exception {
        UploadSession session = uploads.start(new StartUpload(null, "clip.mp4", "video/mp4", PART + 5, null), "admin");
        put(session, 1, bytes(PART));
        db.jdbc.update("update media_assets set upload_expires_at = now() - interval '1 minute' where id = ?", session.assetId());

        new MediaUploadJanitor(assets, uploads).expireAbandonedUploads();

        assertThat(db.text("select status || ' ' || failure_code from media_assets where id = ?", session.assetId()))
                .isEqualTo("FAILED upload_expired");
        assertThat(s3.hasOpenUpload()).as("the parts no longer cost storage").isFalse();
        assertThatThrownBy(() -> uploads.resume(session.assetId(), session.uploadId())).isInstanceOf(IllegalStateException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UploadSession startAndUpload(int size) throws Exception {
        UploadSession session = uploads.start(new StartUpload(null, "clip.mp4", "video/mp4", size, null), "admin");
        put(session, 1, bytes(size));
        return session;
    }

    /** What the browser does: one PUT per part, straight to the signed URL. */
    private void put(UploadSession session, int partNumber, byte[] body) throws Exception {
        HttpResponse<Void> response = browser.send(HttpRequest.newBuilder(URI.create(session.parts().get(partNumber - 1).url()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    /** complete() also nudges the relay in the background; wait until that attempt has been recorded. */
    private void awaitAttempts(UUID assetId, int attempts) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (db.count("select count(*) from media_event_outbox where asset_id = ? and attempts >= ? "
                    + "and status <> 'PROCESSING'", assetId, attempts) == 1) return;
            Thread.sleep(25);
        }
        throw new AssertionError("the relay never attempted delivery");
    }

    private void makeDue() {
        db.jdbc.update("update media_event_outbox set next_attempt_at = now() - interval '1 second'");
    }

    private static byte[] bytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i * 31 + 7);
        return data;
    }
}
