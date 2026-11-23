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
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = MediaTestSchema.ENV, matches = MediaTestSchema.LOCAL_ONLY)
class MediaPresentationJobsTest {
    MediaTestSchema db;
    MediaPresentationJobs jobs;
    MediaPresentationService presentations;
    MediaUploadService uploads;
    AdminUserService users;
    AuditLogService audit;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final String email = "admin@example.test";
    UUID previous;

    @BeforeEach void setup() {
        db = new MediaTestSchema(); uploads = mock(MediaUploadService.class); users = mock(AdminUserService.class);
        audit = mock(AuditLogService.class);
        when(users.authorize(eq(email), anyList())).thenReturn(AdminUserService.Authorization.allowed(AdminUserRole.ADMIN, "test", false));
        var assets = new MediaAssetStore(db.jdbc);
        presentations = new MediaPresentationService(db.jdbc, new MediaArticlePolicy(assets), audit, mapper, db.transactions);
        jobs = new MediaPresentationJobs(db.jdbc, uploads, assets, presentations, users, audit, db.transactions, "");
        previous = asset("READY");
        presentations.replace("yuqi-intro", new MediaPresentationService.Replace(previous, presentations.get("yuqi-intro").revision(), "Current", null), email);
        when(uploads.start(any(), anyString())).thenAnswer(call -> session(asset("UPLOADING")));
    }
    @AfterEach void close() { db.close(); }

    @Test void prepareIsIdempotentAndUploadNeedsBrowserAdministrator() {
        UUID id = UUID.randomUUID();
        var request = new MediaPresentationJobs.Prepare(id, "yuqi-intro", "New");
        var first = jobs.prepare(request, "mcp-admin");
        assertThat(jobs.prepare(request, "mcp-admin")).isEqualTo(first);
        assertThat(first.uploadUrl()).endsWith("/admin/media-intro?job=" + id);
        assertThat(first.previousAssetId()).isEqualTo(previous);
        assertThatThrownBy(() -> jobs.prepare(new MediaPresentationJobs.Prepare(id, "yuqi-intro", "Changed"), email)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> jobs.upload(id, file(), "admin-secret", "admin-secret")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(uploads);
        assertThat(presentations.get("yuqi-intro").assetId()).isEqualTo(previous);
    }

    @Test void readySwapSurvivesDisconnectAndRunsExactlyOnceKeepingPrevious() {
        var job = prepare();
        var upload = jobs.upload(job.id(), file(), email, email);
        jobs.reconcile();
        assertThat(presentations.get("yuqi-intro").assetId()).isEqualTo(previous);
        when(uploads.resume(upload.assetId(), "test-upload")).thenReturn(upload);
        assertThat(jobs.upload(job.id(), file(), email, email).assetId()).isEqualTo(upload.assetId());
        verify(uploads, times(1)).start(any(), anyString());
        db.jdbc.update("update media_assets set status='READY' where id=?", upload.assetId());
        jobs.reconcile();
        var completed = jobs.get(job.id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(presentations.get("yuqi-intro").assetId()).isEqualTo(upload.assetId());
        jobs.reconcile();
        assertThat(presentations.get("yuqi-intro").revision()).isEqualTo(completed.resultRevision());
        assertThat(db.count("select count(*) from media_assets where deleted_at is null")).isEqualTo(2);
        verify(audit, times(2)).log(anyString(), any(), eq("MEDIA_PRESENTATION"), anyString(), isNull(), anyMap(), anyMap());
    }

    @Test void failureCancellationExpiryConflictAndRevocationNeverReplaceCurrent() {
        for (String outcome : List.of("FAILED", "CANCELLED", "EXPIRED", "CONFLICT", "REVOKED")) {
            var job = prepare(); var upload = jobs.upload(job.id(), file(), email, email);
            db.jdbc.update("update media_assets set status=? where id=?", outcome.equals("FAILED") ? "FAILED" : "READY", upload.assetId());
            if (outcome.equals("CANCELLED")) jobs.cancel(job.id(), email);
            if (outcome.equals("EXPIRED")) db.jdbc.update("update media_presentation_jobs set expires_at=now()-interval '1 minute' where id=?", job.id());
            if (outcome.equals("CONFLICT")) presentations.replace("yuqi-intro", new MediaPresentationService.Replace(previous,
                    presentations.get("yuqi-intro").revision(), "Someone else's revision", null), email);
            if (outcome.equals("REVOKED")) when(users.authorize(eq(email), anyList())).thenReturn(null);
            jobs.reconcile();
            assertThat(jobs.get(job.id()).status()).isEqualTo(outcome.equals("REVOKED") ? "FAILED" : outcome);
            assertThat(presentations.get("yuqi-intro").assetId()).isEqualTo(previous);
        }
    }

    @Test void auditFailureRollsBackPointerAndRequestCanRetry() {
        var job = prepare(); var upload = jobs.upload(job.id(), file(), email, email);
        db.jdbc.update("update media_assets set status='READY' where id=?", upload.assetId());
        when(audit.log(anyString(), any(), eq("MEDIA_PRESENTATION"), anyString(), isNull(), anyMap(), anyMap()))
                .thenThrow(new IllegalStateException("audit unavailable"));
        var expired = prepare();
        db.jdbc.update("update media_presentation_jobs set expires_at=now()-interval '1 minute' where id=?", expired.id());
        jobs.reconcile();
        assertThat(jobs.get(job.id()).status()).isEqualTo("WAITING_MEDIA");
        assertThat(presentations.get("yuqi-intro").assetId()).isEqualTo(previous);
        assertThat(jobs.get(expired.id()).status()).isEqualTo("EXPIRED");
        reset(audit);
        jobs.reconcile();
        assertThat(jobs.get(job.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(presentations.get("yuqi-intro").assetId()).isEqualTo(upload.assetId());
    }

    @Test void noPublicDatabaseAccessAndHttpRequiresAdminForEveryOperation() throws Exception {
        assertThat(db.text("select has_table_privilege('anon','media_presentation_jobs','SELECT')::text")).isEqualTo("false");
        assertThat(db.text("select has_table_privilege('authenticated','media_presentation_jobs','UPDATE')::text")).isEqualTo("false");
        var verifier = mock(SupabaseTokenVerifier.class);
        when(verifier.isConfigured()).thenReturn(true);
        when(verifier.verify("admin")).thenReturn(Map.of("email", email));
        when(verifier.verify("editor")).thenReturn(Map.of("email", "editor@example.test"));
        when(users.authorize(eq("editor@example.test"), anyList())).thenReturn(AdminUserService.Authorization.allowed(AdminUserRole.EDITOR, "test", false));
        var http = MockMvcBuilders.standaloneSetup(new MediaPresentationJobsController(jobs))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new AdminAuthFilter("internal-test", verifier, "", users)).build();
        String base = "/api/admin/media/presentation-jobs"; var job = prepare();
        for (String suffix : List.of("", "/"+job.id())) {
            http.perform(get(base+suffix)).andExpect(status().isUnauthorized());
            http.perform(get(base+suffix).header("Authorization", "Bearer editor")).andExpect(status().isForbidden());
        }
        for (String suffix : List.of("", "/"+job.id()+"/upload", "/"+job.id()+"/cancel"))
            http.perform(post(base+suffix).header("Authorization", "Bearer editor").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        http.perform(get(base+"/"+job.id()).header("Authorization", "Bearer admin")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_UPLOAD"));
        http.perform(post(base+"/"+job.id()+"/upload").header("X-Admin-Secret", "internal-test")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(file()))).andExpect(status().isForbidden());
    }

    MediaPresentationJobs.View prepare() { return jobs.prepare(new MediaPresentationJobs.Prepare(UUID.randomUUID(), "yuqi-intro", "New"), email); }
    MediaUploadService.StartUpload file() { return new MediaUploadService.StartUpload("VIDEO", "test.mp4", "video/mp4", 123, "New"); }
    MediaUploadService.UploadSession session(UUID id) { return new MediaUploadService.UploadSession(id, "test-upload", 5242880, 1, List.of(), List.of(), Instant.now().plusSeconds(3600), "test.mp4", "video/mp4", 123); }
    UUID asset(String status) {
        UUID id = UUID.randomUUID();
        db.jdbc.update("""
            insert into media_assets(id,kind,status,title,original_filename,content_type,size_bytes,source_version,
                source_key,profile_version,manifest,created_by,upload_id)
            values (?,'VIDEO',?,'Test','test.mp4','video/mp4',123,1,'private/test.mp4','test',?::jsonb,'test','test-upload')
            """, id, status, """
            {"basePath":"media/test/v1","hls":"master.m3u8","poster":{"path":"poster.jpg"},"durationSeconds":8,
             "renditions":[{"width":640,"height":360,"playlist":"360p/index.m3u8"}]}
            """);
        return id;
    }
}
