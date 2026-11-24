package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import site.yuqi.admin.controller.GlobalExceptionHandler;
import site.yuqi.admin.security.AdminAuthFilter;
import site.yuqi.admin.security.SupabaseTokenVerifier;
import site.yuqi.admin.service.AdminUserService;
import site.yuqi.admin.service.AuditLogService;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.db.JobFlow;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.intake.WorkEvent;
import site.yuqi.media.intake.WorkIntake;
import site.yuqi.media.store.S3ObjectStore;
import site.yuqi.media.work.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Fresh upload, actual transcode, ADMIN HTTP deletion, then real HLS/MP4 decoding without the source. */
@EnabledIfEnvironmentVariable(named = MediaTestSchema.ENV, matches = MediaTestSchema.LOCAL_ONLY)
class MediaOriginalDeletionEndToEndTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            KafkaAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class WebOnly { }

    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final HttpClient http = HttpClient.newHttpClient();
    final String adminToken = UUID.randomUUID().toString();

    @Test @Timeout(180)
    void uploadTranscodeDeleteOriginalAndStillDecodeEveryPublishedStream() throws Exception {
        Path input = root.resolve("generated.mp4");
        command("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                "testsrc2=size=640x360:rate=24:duration=2", "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-c:v", "libx264", "-preset", "ultrafast", "-threads", "2", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", input.toString());
        try (var db = new MediaTestSchema(); var storage = new FakeS3()) {
            var settings = Map.of("portfolio.media-pipeline.storage.endpoint", storage.endpoint(),
                    "portfolio.media-pipeline.storage.access-key", "test",
                    "portfolio.media-pipeline.storage.secret-key", "test",
                    "portfolio.media-pipeline.worker.work-dir", root.resolve("work").toString(),
                    "portfolio.media-pipeline.tools.x264-preset", "ultrafast");
            var binder = new Binder(new MapConfigurationPropertySource(settings));
            var properties = binder.bindOrCreate("portfolio.media-pipeline", MediaProperties.class);
            var adminProperties = binder.bindOrCreate("portfolio.media-pipeline", MediaPipelineProperties.class);
            var assets = new MediaAssetStore(db.jdbc);
            var originals = new OriginalsStore(adminProperties);
            var uploads = new MediaUploadService(assets, originals, adminProperties, mock(MediaOutboxRelay.class), db.transactions, mapper);
            var audit = mock(AuditLogService.class);
            var deletion = new MediaOriginalDeletionService(assets, originals, uploads, audit, mapper, db.transactions);
            var repository = new MediaRepository(db.jdbc);
            var flow = new JobFlow(repository, db.transactions);
            var directories = new WorkDirectories(properties);
            var objectStore = new S3ObjectStore(properties);
            var sources = new SourceCache(objectStore, properties, directories);
            var outputs = new Outputs(objectStore, properties);
            var extras = new VideoExtrasHandlers();
            var images = extras.imageTool(properties);
            var runner = new JobRunner(repository, flow, directories, properties, List.of(
                    new PlanHandler(repository, flow, sources, properties, images, mapper),
                    new VideoRenditionHandler(repository, flow, sources, outputs, properties, mapper),
                    extras.videoPosterHandler(repository, flow, sources, outputs, properties, images, mapper),
                    extras.videoMp4Handler(repository, flow, sources, outputs, properties, mapper),
                    new PublishHandler(repository, flow, outputs, mapper)));
            var intake = new WorkIntake(repository, flow, runner);
            try (var web = web(new MediaAdminController(uploads, mock(MediaManagementService.class)),
                    new MediaOriginalDeletionController(deletion), new GlobalExceptionHandler(),
                    new AdminAuthFilter(adminToken, mock(SupabaseTokenVerifier.class), "", mock(AdminUserService.class)))) {
                String base = "http://127.0.0.1:" + web.getWebServer().getPort() + "/api/admin/media";
                JsonNode session = request("POST", base + "/uploads", mapper.writeValueAsString(Map.of(
                        "kind", "VIDEO", "filename", "generated.mp4", "contentType", "video/mp4", "sizeBytes", Files.size(input))), 201);
                byte[] bytes = Files.readAllBytes(input);
                long partSize = session.path("partSizeBytes").asLong();
                for (JsonNode part : session.path("parts")) {
                    int start = (int) ((part.path("partNumber").asInt() - 1) * partSize);
                    byte[] chunk = Arrays.copyOfRange(bytes, start, (int) Math.min(bytes.length, start + partSize));
                    var sent = http.send(HttpRequest.newBuilder(URI.create(part.path("url").asText()))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build(), HttpResponse.BodyHandlers.discarding());
                    assertThat(sent.statusCode()).isEqualTo(200);
                }
                UUID id = UUID.fromString(session.path("assetId").asText());
                String assetUrl = base + "/" + id;
                assertThat(request("POST", assetUrl + "/upload-complete", mapper.writeValueAsString(
                        Map.of("uploadId", session.path("uploadId").asText())), 200).path("status").asText()).isEqualTo("PROCESSING");
                WorkEvent event = mapper.readValue(db.text("select payload::text from media_event_outbox where asset_id = ?", id), WorkEvent.class);
                assertThat(intake.accept(event)).isEqualTo(WorkIntake.Outcome.ACCEPTED);
                for (int i = 0; i < 20; i++) {
                    var job = repository.claimNext("original-delete-e2e", properties.worker().leaseSeconds());
                    if (job.isEmpty()) break;
                    runner.runOnce(job.get(), "original-delete-e2e");
                }
                var ready = request("GET", assetUrl, null, 200);
                assertThat(ready.path("status").asText()).isEqualTo("READY");
                assertThat(ready.path("jobs")).allSatisfy(job -> assertThat(job.path("status").asText()).isEqualTo("SUCCEEDED"));
                var manifest = ready.path("manifest");
                String playbackBase = storage.endpoint() + "/portfolio-media-public/" + manifest.path("basePath").asText() + "/";
                decode(playbackBase + manifest.path("hls").asText());
                db.jdbc.update("""
                        insert into media_presentations(placement_key,asset_id,presentation,enabled)
                        values ('yuqi-intro', ?, '{"id":"yuqi-intro","title":"E2E video","href":"/"}'::jsonb, true)
                        """, id);
                String publicRow = db.text("select row_to_json(p)::text from media_public p where id = ?", id);
                String catalog = db.text("select row_to_json(p)::text from media_demo_catalog p where asset_id = ?", id);
                Map<String, byte[]> publicFiles = new LinkedHashMap<>();
                storage.objects.forEach((key, value) -> { if (key.startsWith("/portfolio-media-public/")) publicFiles.put(key, value.clone()); });
                String originalPath = "/portfolio-media-originals/" + assets.find(id).orElseThrow().sourceKey();
                assertThat(storage.objects).containsKey(originalPath);
                assertThat(request("GET", assetUrl + "/original-deletion-check", null, 200).path("deletable").asBoolean()).isTrue();
                var deleted = request("DELETE", assetUrl + "/original?expectedVersion=0", null, 200);
                assertThat(deleted.path("status").asText()).isEqualTo("READY");
                assertThat(deleted.path("originalStatus").asText()).isEqualTo("DELETED");
                assertThat(deleted.path("originalDeletedAt").isTextual()).isTrue();
                assertThat(deleted.path("manifest")).isEqualTo(manifest);
                assertThat(deleted.path("usages")).isNotEmpty();
                assertThat(storage.objects).doesNotContainKey(originalPath);
                assertThat(storage.objects.keySet()).containsExactlyInAnyOrderElementsOf(publicFiles.keySet());
                publicFiles.forEach((key, value) -> assertThat(storage.objects.get(key)).isEqualTo(value));
                assertThat(db.text("select row_to_json(p)::text from media_public p where id = ?", id)).isEqualTo(publicRow);
                assertThat(db.text("select row_to_json(p)::text from media_demo_catalog p where asset_id = ?", id)).isEqualTo(catalog);

                // Decode actual HTTP-served outputs after the original is gone, including audio and MP4.
                decode(playbackBase + manifest.path("hls").asText());
                decode(playbackBase + manifest.path("mp4").path("path").asText());
                for (JsonNode rendition : manifest.path("renditions")) decode(playbackBase + rendition.path("playlist").asText());
                long originalReads = storage.requests.stream().filter(r -> r.method().equals("GET") && r.path().equals(originalPath)).count();
                assertThat(intake.accept(event)).isEqualTo(WorkIntake.Outcome.STALE);
                assertThat(flow.acceptWork(id, 1, "different-profile")).isFalse();
                assertThat(repository.claimNext("no-source-worker", 30)).isEmpty();
                request("POST", assetUrl + "/retry", null, 409);
                assertThat(storage.requests.stream().filter(r -> r.method().equals("GET") && r.path().equals(originalPath)).count())
                        .isEqualTo(originalReads);
                assertThat(request("DELETE", assetUrl + "/original?expectedVersion=0", null, 200).path("originalStatus").asText()).isEqualTo("DELETED");
                assertThat(storage.requests.stream().filter(r -> r.method().equals("DELETE")).count()).isEqualTo(1);
            } finally { runner.stop(); }
        }
    }

    private void decode(String url) throws Exception {
        command("ffmpeg", "-hide_banner", "-loglevel", "error", "-i", url, "-map", "0:v", "-map", "0:a?", "-f", "null", "-");
    }

    private void command(String... command) throws Exception {
        Path log = root.resolve("ffmpeg.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private JsonNode request(String method, String url, String body, int expected) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).header("X-Admin-Secret", adminToken)
                .header("Content-Type", "application/json").method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return mapper.readTree(response.body());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ServletWebServerApplicationContext web(Object... components) {
        var app = new SpringApplication(WebOnly.class);
        app.addInitializers(context -> {
            var beans = (GenericApplicationContext) context;
            for (int i = 0; i < components.length; i++) {
                Object component = components[i];
                beans.registerBean("originalDeleteTest" + i, (Class) component.getClass(), () -> component);
            }
        });
        return (ServletWebServerApplicationContext) app.run("--spring.config.name=original-delete-e2e",
                "--server.address=127.0.0.1", "--server.port=0", "--portfolio.media-pipeline.enabled=true",
                "--logging.level.root=WARN", "--spring.main.banner-mode=off");
    }
}
