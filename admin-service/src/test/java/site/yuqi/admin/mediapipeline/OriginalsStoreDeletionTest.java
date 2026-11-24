package site.yuqi.admin.mediapipeline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class OriginalsStoreDeletionTest {
    static final String BUCKET = "portfolio-media-originals";

    static MediaPipelineProperties properties(String endpoint) {
        return new Binder(new MapConfigurationPropertySource(Map.of(
                "portfolio.media-pipeline.storage.endpoint", endpoint,
                "portfolio.media-pipeline.storage.access-key", "test",
                "portfolio.media-pipeline.storage.secret-key", "test")))
                .bindOrCreate("portfolio.media-pipeline", MediaPipelineProperties.class);
    }

    static String key(UUID id) { return "originals/" + id + "/0123456789abcdef0123/clip.mp4"; }

    @Test void signsOnlyTheExactPrivateObjectAndAbsenceIsIdempotent() throws Exception {
        try (var storage = new FakeS3()) {
            UUID id = UUID.randomUUID();
            String key = key(id);
            storage.objects.put("/" + BUCKET + "/" + key, new byte[]{1});
            storage.objects.put("/portfolio-media-public/media/" + id + "/master.m3u8", new byte[]{2});
            var originals = new OriginalsStore(properties(storage.endpoint()));
            originals.deleteOriginal(id, BUCKET, key, "1");
            originals.deleteOriginal(id, BUCKET, key, "1");
            assertThat(storage.objects).containsOnlyKeys("/portfolio-media-public/media/" + id + "/master.m3u8");
            assertThat(storage.requests).extracting(FakeS3.Request::method).containsExactly("HEAD", "DELETE", "HEAD", "HEAD");
            assertThat(storage.requests).allSatisfy(request -> {
                assertThat(request.path()).isEqualTo("/" + BUCKET + "/" + key);
                assertThat(request.query()).isNull();
                if (request.method().equals("DELETE")) {
                    assertThat(request.authorization()).startsWith("GOOG4-HMAC-SHA256 ");
                    assertThat(request.authorization()).contains("/auto/storage/goog4_request");
                    assertThat(request.authorization()).doesNotContain("x-amz-");
                    assertThat(request.generationCondition()).isEqualTo("1");
                    assertThat(request.authorization()).contains("x-goog-if-generation-match");
                } else {
                    assertThat(request.authorization()).startsWith("AWS4-HMAC-SHA256 ");
                }
            });
        }
    }

    @Test void rejectsOtherAssetsPublicBucketsTraversalAndBulkPathsBeforeHttp() throws Exception {
        try (var storage = new FakeS3()) {
            UUID id = UUID.randomUUID();
            var originals = new OriginalsStore(properties(storage.endpoint()));
            for (String key : new String[]{key(UUID.randomUUID()), "media/" + id + "/master.m3u8",
                    "originals/" + id + "/0123456789abcdef0123/..", key(id) + "/../other.mp4",
                    key(id).replace("clip", "%2e%2e%2fclip"), "originals/" + id + "/", key(id) + "?delete"}) {
                assertThatThrownBy(() -> originals.deleteOriginal(id, BUCKET, key, "1")).isInstanceOf(IllegalStateException.class);
            }
            assertThatThrownBy(() -> originals.deleteOriginal(id, "portfolio-media-public", key(id), "1"))
                    .isInstanceOf(IllegalStateException.class);
            var unsafe = new Binder(new MapConfigurationPropertySource(Map.of(
                    "portfolio.media-pipeline.storage.endpoint", storage.endpoint(),
                    "portfolio.media-pipeline.storage.originals-bucket", "portfolio-media-public")))
                    .bindOrCreate("portfolio.media-pipeline", MediaPipelineProperties.class);
            assertThatThrownBy(() -> new OriginalsStore(unsafe).deleteOriginal(id, "portfolio-media-public", key(id), "1"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(storage.requests).isEmpty();
        }
    }

    @Test void failureAndAcceptedButUnfinishedDeletionAreNotSuccess() throws Exception {
        try (var storage = new FakeS3()) {
            UUID id = UUID.randomUUID();
            storage.objects.put("/" + BUCKET + "/" + key(id), new byte[]{1});
            var originals = new OriginalsStore(properties(storage.endpoint()));
            for (int status : new int[]{202, 403, 500, 503}) {
                storage.deleteStatus = status;
                assertThatThrownBy(() -> originals.deleteOriginal(id, BUCKET, key(id), "1")).isInstanceOf(IOException.class);
                assertThat(storage.objects).hasSize(1);
            }
        }
    }

    @Test void versionedObjectsAreNotReplacedByDeleteMarkers() throws Exception {
        try (var storage = new FakeS3()) {
            UUID id = UUID.randomUUID();
            storage.versioned = true;
            storage.objects.put("/" + BUCKET + "/" + key(id), new byte[]{1});
            var originals = new OriginalsStore(properties(storage.endpoint()));
            assertThatThrownBy(() -> originals.deleteOriginal(id, BUCKET, key(id), "1")).isInstanceOf(IOException.class);
            assertThat(storage.requests).extracting(FakeS3.Request::method).containsExactly("HEAD");
            assertThat(storage.objects).hasSize(1);
        }
    }

    @Test void providerRejectionsKeepSafeDiagnosticsWithoutTheResponseBody() throws Exception {
        try (var storage = new FakeS3()) {
            UUID id = UUID.randomUUID();
            storage.objects.put("/" + BUCKET + "/" + key(id), new byte[]{1});
            storage.deleteStatus = 400;
            storage.deleteErrorBody = "<Error><Code>ExcessHeaderValues</Code><Details>private request data</Details></Error>";
            var originals = new OriginalsStore(properties(storage.endpoint()));
            var failure = catchThrowableOfType(() -> originals.deleteOriginal(id, BUCKET, key(id), "1"),
                    OriginalsStore.DeletionRequestException.class);
            assertThat(failure.status()).isEqualTo(400);
            assertThat(failure.providerCode()).isEqualTo("ExcessHeaderValues");
            assertThat(failure.getMessage()).doesNotContain("private request data", key(id));
            assertThat(storage.objects).hasSize(1);
        }
    }

    @Test void wrongGenerationAndUnsupportedStoresFailClosed() throws Exception {
        try (var storage = new FakeS3()) {
            UUID id = UUID.randomUUID();
            String path = "/" + BUCKET + "/" + key(id);
            storage.objects.put(path, new byte[]{1});
            var originals = new OriginalsStore(properties(storage.endpoint()));
            storage.beforeDelete = ignored -> storage.generation = "2";
            assertThatThrownBy(() -> originals.deleteOriginal(id, BUCKET, key(id), "1")).isInstanceOf(IOException.class);
            assertThat(storage.objects).containsKey(path);
            // A retry must not silently switch to generation 2.
            assertThatThrownBy(() -> originals.deleteOriginal(id, BUCKET, key(id), "1")).hasMessageContaining("generation changed");
            assertThat(storage.requests.stream().filter(r -> r.method().equals("DELETE")).count()).isEqualTo(1);
            storage.generation = null;
            assertThatThrownBy(() -> originals.deleteOriginal(id, BUCKET, key(id), "1")).hasMessageContaining("conditional deletion");
            assertThat(storage.requests.stream().filter(r -> r.method().equals("DELETE")).count()).isEqualTo(1);
        }
    }

    @Test void successfulDeleteResponseAloneDoesNotProveAbsence() throws Exception {
        try (var storage = new FakeS3()) {
            UUID id = UUID.randomUUID();
            storage.objects.put("/" + BUCKET + "/" + key(id), new byte[]{1});
            storage.retainOnDelete = true;
            var originals = new OriginalsStore(properties(storage.endpoint()));
            assertThatThrownBy(() -> originals.deleteOriginal(id, BUCKET, key(id), "1")).hasMessageContaining("absent");
            assertThat(storage.objects).hasSize(1);
        }
    }
}
