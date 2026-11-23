package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import site.yuqi.admin.controller.GlobalExceptionHandler;
import site.yuqi.admin.domain.AdminUserRole;
import site.yuqi.admin.security.AdminAuthFilter;
import site.yuqi.admin.security.SupabaseTokenVerifier;
import site.yuqi.admin.service.AdminUserService;
import site.yuqi.admin.service.AuditLogService;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = MediaTestSchema.ENV, matches = MediaTestSchema.LOCAL_ONLY)
class MediaPresentationTest {
    MediaTestSchema db;
    MediaPresentationService service;
    AuditLogService audit;
    final ObjectMapper mapper = new ObjectMapper();
    final String key = "yuqi-intro";

    @BeforeEach void setup() {
        db = new MediaTestSchema();
        audit = mock(AuditLogService.class);
        service = new MediaPresentationService(db.jdbc, new MediaArticlePolicy(new MediaAssetStore(db.jdbc)), audit, mapper, db.transactions);
    }
    @AfterEach void close() { db.close(); }

    @Test void replaceDisableAndRestorePreserveAssetsAndPublicVisibility() {
        UUID first = asset("READY", "VIDEO"), second = asset("READY", "VIDEO");
        var original = service.replace(key, request(first, service.get(key).revision()), "admin");
        assertThat(original.presentation().path("playback").asText()).isEqualTo("asset");
        assertThat(original.presentation().has("src")).isFalse();
        assertThat(db.text("select asset_id::text from media_demo_catalog")).isEqualTo(first.toString());
        var replacement = service.replace(key, request(second, original.revision()), "admin");
        assertThat(replacement.revision()).isNotEqualTo(original.revision());
        assertThat(db.text("select asset_id::text from media_demo_catalog")).isEqualTo(second.toString());
        assertThatThrownBy(() -> service.replace(key, request(first, original.revision()), "admin"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.disable(key, original.revision(), "admin"))
                .isInstanceOf(IllegalStateException.class);
        var disabled = service.disable(key, replacement.revision(), "admin");
        assertThat(disabled.enabled()).isFalse();
        assertThat(db.count("select count(*) from media_demo_catalog")).isZero();
        service.replace(key, request(first, disabled.revision()), "admin");
        assertThat(db.count("select count(*) from media_assets where status='READY' and deleted_at is null")).isEqualTo(2);
        assertThat(db.count("select count(*) from media_event_outbox")).isZero();
        verify(audit, times(4)).log(eq("admin"), any(), eq("MEDIA_PRESENTATION"), eq(key), isNull(), anyMap(), anyMap());
    }

    @Test void rejectsUnreadyImageDeletedInvalidManifestAndUnknownPlacement() {
        String revision = service.get(key).revision();
        for (String status : List.of("UPLOADING", "PROCESSING", "FAILED"))
            assertThatThrownBy(() -> service.replace(key, request(asset(status, "VIDEO"), revision), "admin"))
                    .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.replace(key, request(asset("READY", "IMAGE"), revision), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        UUID deleted = asset("READY", "VIDEO");
        db.jdbc.update("update media_assets set deleted_at=now() where id=?", deleted);
        assertThatThrownBy(() -> service.replace(key, request(deleted, revision), "admin")).isInstanceOf(IllegalStateException.class);
        UUID invalid = asset("READY", "VIDEO");
        db.jdbc.update("update media_assets set manifest=jsonb_set(manifest, '{hls}', '\"../private/master.m3u8\"') where id=?", invalid);
        assertThatThrownBy(() -> service.replace(key, request(invalid, revision), "admin")).isInstanceOf(IllegalStateException.class);
        db.jdbc.update("update media_assets set manifest=jsonb_set(manifest, '{hls}', '\"preview.mp4\"') where id=?", invalid);
        assertThatThrownBy(() -> service.replace(key, request(invalid, revision), "admin")).isInstanceOf(IllegalStateException.class);
        db.jdbc.update("update media_assets set manifest=jsonb_set(jsonb_set(manifest, '{hls}', '\"master.m3u8\"'), '{renditions,0,playlist}', '\"preview.mp4\"') where id=?", invalid);
        assertThatThrownBy(() -> service.replace(key, request(invalid, revision), "admin")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.get("arbitrary-placement")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.count("select count(*) from media_presentations")).isZero();
        verifyNoInteractions(audit);
    }

    @Test void validatesChaptersAndRollsBackWhenAuditFails() {
        UUID id = asset("READY", "VIDEO");
        String revision = service.get(key).revision();
        for (double seconds : new double[]{-1, 8, Double.NaN})
            assertThatThrownBy(() -> service.replace(key, new MediaPresentationService.Replace(id, revision, "Intro",
                    List.of(new MediaPresentationService.Chapter(seconds, "Start"))), "admin"))
                    .isInstanceOf(IllegalArgumentException.class);
        when(audit.log(anyString(), any(), eq("MEDIA_PRESENTATION"), anyString(), isNull(), anyMap(), anyMap()))
                .thenThrow(new IllegalStateException("audit unavailable"));
        assertThatThrownBy(() -> service.replace(key, request(id, revision), "admin")).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(key).exists()).isFalse();
    }

    @Test void concurrentFirstCreationHasOneWinner() throws Exception {
        UUID first = asset("READY", "VIDEO"), second = asset("READY", "VIDEO");
        String revision = service.get(key).revision();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (UUID id : List.of(first, second)) results.add(pool.submit(() -> {
                start.await();
                try { service.replace(key, request(id, revision), "admin"); return true; }
                catch (IllegalStateException stale) { return false; }
            }));
            start.countDown();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(db.count("select count(*) from media_presentations")).isEqualTo(1);
    }

    @Test void httpRequiresAdministratorAndFreshRevision() throws Exception {
        var verifier = mock(SupabaseTokenVerifier.class);
        var users = mock(AdminUserService.class);
        when(verifier.isConfigured()).thenReturn(true);
        for (String role : List.of("admin", "editor")) {
            when(verifier.verify(role)).thenReturn(Map.of("email", role+"@example.test"));
            when(users.authorize(eq(role+"@example.test"), anyList())).thenReturn(
                    AdminUserService.Authorization.allowed(AdminUserRole.valueOf(role.toUpperCase()), "test", false));
        }
        var http = MockMvcBuilders.standaloneSetup(new MediaPresentationController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new AdminAuthFilter("", verifier, "", users)).build();
        String path = "/api/admin/media/presentations/"+key;
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (var method : List.of(org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.PUT, org.springframework.http.HttpMethod.DELETE))
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(method, path)
                    .header("Authorization", "Bearer editor")).andExpect(status().isForbidden());
        http.perform(get(path).header("Authorization", "Bearer admin")).andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false)).andExpect(header().string("Cache-Control", "private, no-store, max-age=0"));
        String body = mapper.writeValueAsString(request(asset("READY", "VIDEO"), service.get(key).revision()));
        http.perform(put(path).header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        http.perform(put(path).header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        http.perform(delete(path).header("Authorization", "Bearer admin").param("expectedRevision", service.get(key).revision()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    }

    private MediaPresentationService.Replace request(UUID id, String revision) {
        return new MediaPresentationService.Replace(id, revision, "Test introduction",
                List.of(new MediaPresentationService.Chapter(0d, "Start"), new MediaPresentationService.Chapter(3d, "Next")));
    }
    private UUID asset(String status, String kind) {
        UUID id = UUID.randomUUID();
        db.jdbc.update("""
            insert into media_assets(id,kind,status,title,original_filename,content_type,size_bytes,source_version,
                source_key,profile_version,manifest,created_by)
            values (?,?,?,'Test','test.mp4','video/mp4',123,1,'private/test.mp4','test',?::jsonb,'test')
            """, id, kind, status, """
            {"basePath":"media/test/v1","hls":"master.m3u8","poster":{"path":"poster.jpg"},"durationSeconds":8,
             "renditions":[{"width":640,"height":360,"playlist":"360p/index.m3u8"}]}
            """);
        return id;
    }
}
