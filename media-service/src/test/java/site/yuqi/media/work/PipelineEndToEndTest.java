package site.yuqi.media.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.db.JobFlow;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.intake.WorkEvent;
import site.yuqi.media.intake.WorkIntake;
import site.yuqi.media.media.HlsPlaylist;
import site.yuqi.media.model.Job;
import site.yuqi.media.support.DirectoryObjectStore;
import site.yuqi.media.support.TestDatabase;
import site.yuqi.media.support.TestProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seven steps with the real tools: a clip FFmpeg generates on the spot goes in, and what comes
 * out is a published asset whose playlists and files are all present. Needs a local PostgreSQL and
 * FFmpeg and libvips on the PATH; without the tools the tests are skipped.
 */
@EnabledIfEnvironmentVariable(named = TestDatabase.ENV, matches = TestDatabase.LOCAL_ONLY)
class PipelineEndToEndTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private TestDatabase db;
    private Path root;
    private DirectoryObjectStore store;
    private MediaProperties properties;
    private MediaRepository repository;
    private JobRunner runner;
    private WorkIntake intake;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(available("ffmpeg", "-version") && available("ffprobe", "-version")
                && available("vips", "--version"), "FFmpeg and libvips are not installed");
        db = new TestDatabase();
        root = Files.createTempDirectory("media-e2e-");
        store = new DirectoryObjectStore(root.resolve("buckets"));
        properties = TestProperties.with(Map.of(
                "portfolio.media-pipeline.worker.work-dir", root.resolve("work").toString(),
                "portfolio.media-pipeline.tools.x264-preset", "ultrafast"));
        repository = new MediaRepository(db.jdbc);
        JobFlow flow = new JobFlow(repository, db.transactions);
        WorkDirectories directories = new WorkDirectories(properties);
        SourceCache sources = new SourceCache(store, properties, directories);
        Outputs outputs = new Outputs(store, properties);
        VideoExtrasHandlers extras = new VideoExtrasHandlers();
        var images = extras.imageTool(properties);
        runner = new JobRunner(repository, flow, directories, properties, List.of(
                new PlanHandler(repository, flow, sources, properties, images, mapper),
                new VideoRenditionHandler(repository, flow, sources, outputs, properties, mapper),
                extras.videoPosterHandler(repository, flow, sources, outputs, properties, images, mapper),
                extras.videoMp4Handler(repository, flow, sources, outputs, properties, mapper),
                new ImageVariantsHandler(repository, flow, sources, outputs, images, mapper),
                new PublishHandler(repository, flow, outputs, mapper)));
        intake = new WorkIntake(repository, flow, runner);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    @Test
    void aVideoBecomesAlignedRenditionsAndIsPublishedLast() throws Exception {
        UUID asset = upload("VIDEO", "clip.mp4", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=8",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=8", "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest");

        assertThat(intake.accept(event(asset))).isEqualTo(WorkIntake.Outcome.ACCEPTED);
        runOneJob();
        assertThat(db.assetStatus(asset)).as("planned, nothing public yet").isEqualTo("PROCESSING");
        assertThat(db.count("select count(*) from media_public where id = ?", asset)).isZero();
        runUntilIdle();

        assertThat(db.assetStatus(asset)).isEqualTo("READY");
        JsonNode manifest = manifest(asset);
        Path base = store.path("portfolio-media-public", manifest.path("basePath").asText());
        assertThat(manifest.path("renditions")).extracting(r -> r.path("name").asText()).containsExactly("360p", "720p");
        assertThat(manifest.path("durationSeconds").asDouble()).isBetween(7.9, 8.1);
        assertThat(manifest.path("hasAudio").asBoolean()).isTrue();
        assertThat(manifest.has("hlsCapped")).as("nothing above 720p to cap").isFalse();
        assertThat(base.resolve(manifest.path("poster").path("path").asText())).isNotEmptyFile();
        assertThat(base.resolve(manifest.path("mp4").path("path").asText())).isNotEmptyFile();

        String master = Files.readString(base.resolve(manifest.path("hls").asText()));
        assertThat(master).contains("RESOLUTION=640x360", "RESOLUTION=1280x720", "CODECS=\"avc1.", "mp4a.40.2");
        List<Double> low = null;
        for (JsonNode rendition : manifest.path("renditions")) {
            Path playlistFile = base.resolve(rendition.path("playlist").asText());
            HlsPlaylist playlist = HlsPlaylist.read(playlistFile);
            assertThat(playlist.ended()).isTrue();
            assertThat(playlistFile.resolveSibling(playlist.initUri())).isNotEmptyFile();
            for (HlsPlaylist.Segment segment : playlist.segments()) {
                assertThat(playlistFile.resolveSibling(segment.uri())).isNotEmptyFile();
            }
            List<Double> cuts = playlist.segments().stream().map(HlsPlaylist.Segment::seconds).toList();
            assertThat(cuts).as("6 s segments, the remainder last").containsExactly(6.0, 2.0);
            if (low == null) low = cuts;
            assertThat(cuts).as("every rendition cuts at the same instants").isEqualTo(low);
        }
    }

    @Test
    void anImageBecomesAvifAndWebpInEveryWidthPlusAJpeg() throws Exception {
        UUID asset = upload("IMAGE", "shot.png", "-f", "lavfi", "-i", "testsrc2=size=1500x1000:rate=1:duration=1",
                "-frames:v", "1");

        intake.accept(event(asset));
        runUntilIdle();

        assertThat(db.assetStatus(asset)).isEqualTo("READY");
        JsonNode manifest = manifest(asset);
        Path base = store.path("portfolio-media-public", manifest.path("basePath").asText());
        assertThat(manifest.path("width").asInt()).isEqualTo(1500);
        assertThat(manifest.path("variants")).hasSize(10);
        assertThat(manifest.path("variants")).extracting(v -> v.path("width").asInt()).containsOnly(320, 640, 960, 1280, 1500);
        assertThat(manifest.path("fallback").path("format").asText()).isEqualTo("jpeg");
        assertThat(manifest.path("fallback").path("width").asInt()).isEqualTo(1280);
        for (JsonNode variant : manifest.path("variants")) {
            assertThat(base.resolve(variant.path("path").asText())).isNotEmptyFile();
            assertThat(variant.path("height").asInt()).isEqualTo(Math.round(variant.path("width").asInt() * 1000 / 1500f));
        }
    }

    @Test
    void aFileThatIsNotAVideoFailsOnceWithAReasonAndIsNotRetried() throws Exception {
        UUID asset = db.processingAsset("VIDEO");
        Path original = store.path("portfolio-media-originals", "originals/" + asset + "/u1/clip.mp4");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "this is not a video");

        intake.accept(event(asset));
        runUntilIdle();

        assertThat(db.assetStatus(asset)).isEqualTo("FAILED");
        assertThat(db.jdbc.queryForObject("select failure_code from media_assets where id = ?", String.class, asset))
                .isEqualTo("unsupported_input");
        assertThat(db.jdbc.queryForObject("select failure_message from media_assets where id = ?", String.class, asset))
                .startsWith("not a readable video").doesNotContain(root.toString());
        assertThat(db.count("select attempt from media_jobs where asset_id = ? and job_type = 'PLAN'", asset)).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Generates an original with FFmpeg, stores it where an upload would be, and binds it to a new asset. */
    private UUID upload(String kind, String name, String... ffmpegInput) throws Exception {
        UUID asset = db.processingAsset(kind);
        Path original = store.path("portfolio-media-originals", "originals/" + asset + "/u1/clip.mp4");
        Files.createDirectories(original.getParent());
        Path generated = root.resolve(name);
        List<String> command = new java.util.ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
        command.addAll(List.of(ffmpegInput));
        command.add(generated.toString());
        Process process = new ProcessBuilder(command).inheritIO().start();
        assertThat(process.waitFor()).as("ffmpeg could not generate the test input").isZero();
        Files.move(generated, original);
        return asset;
    }

    private static WorkEvent event(UUID asset) {
        return new WorkEvent(UUID.randomUUID().toString(), Instant.now(), null, null, null, 1, "MEDIA",
                asset.toString(), 1, null, "media:" + asset + ":1:v1", "MEDIA_PROCESS",
                "originals/" + asset + "/u1/clip.mp4", "v1");
    }

    private boolean runOneJob() throws Exception {
        Optional<Job> job = repository.claimNext("test-worker", properties.worker().leaseSeconds());
        if (job.isPresent()) runner.runOnce(job.get(), "test-worker");
        return job.isPresent();
    }

    private void runUntilIdle() throws Exception {
        for (int i = 0; i < 50 && runOneJob(); i++) { }
    }

    private JsonNode manifest(UUID asset) throws Exception {
        return mapper.readTree(db.jdbc.queryForObject("select manifest::text from media_public where id = ?", String.class, asset));
    }

    private static boolean available(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor() == 0;
        } catch (Exception missing) {
            return false;
        }
    }
}
