package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import site.yuqi.admin.controller.GlobalExceptionHandler;
import site.yuqi.admin.domain.AdminUserRole;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.security.AdminAuthFilter;
import site.yuqi.admin.security.SupabaseTokenVerifier;
import site.yuqi.admin.service.AdminUserService;
import site.yuqi.admin.service.AuditLogService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = MediaTestSchema.ENV, matches = MediaTestSchema.LOCAL_ONLY)
class MediaManagementTest {
    MediaTestSchema db;
    MediaAssetStore assets;
    MediaUploadService uploads;
    MediaManagementService service;
    AuditLogService audit;

    @BeforeEach void setUp() {
        db = new MediaTestSchema();
        // Minimal fixtures for the existing article tables, not new production tables.
        for (String table : new String[]{"\"Projects\"", "\"Blogs\"", "life_blogs"})
            db.jdbc.execute("create table " + table + " (id text primary key, content text, image_url text)");
        assets = new MediaAssetStore(db.jdbc);
        uploads = new MediaUploadService(assets, mock(OriginalsStore.class), null, mock(MediaOutboxRelay.class),
                db.transactions, new ObjectMapper().findAndRegisterModules());
        audit = mock(AuditLogService.class);
        service = new MediaManagementService(assets, uploads, audit, db.transactions);
    }

    @AfterEach void close() { db.close(); }

    @Test void editsOnlyMetadataAndRejectsStaleVersion() {
        UUID id = asset("READY");
        var result = service.update(id, new MediaManagementService.Metadata(0, "New title", "Caption"), "operator");
        assertThat(result.title()).isEqualTo("New title");
        assertThat(result.description()).isEqualTo("Caption");
        assertThat(result.metadataVersion()).isEqualTo(1);
        assertThat(result.sourceVersion()).isEqualTo(1);
        assertThat(result.manifest().path("url").asText()).isEqualTo("https://public.example/video.mp4");
        assertThat(db.count("select count(*) from media_event_outbox")).isZero();
        assertThatThrownBy(() -> service.update(id, new MediaManagementService.Metadata(0, "Stale", ""), "operator"))
                .isInstanceOf(IllegalStateException.class);
        verify(audit).log(eq("operator"), eq(AuditAction.UPDATE), eq("MEDIA"), eq(id.toString()), eq(1), anyMap(), anyMap());
    }

    @Test void concurrentEditsHaveExactlyOneWinner() throws Exception {
        UUID id = asset("READY");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            Callable<Boolean> edit = () -> {
                ready.countDown(); start.await();
                try { service.update(id, new MediaManagementService.Metadata(0, "Concurrent", ""), "operator"); return true; }
                catch (IllegalStateException stale) { return false; }
            };
            var first = pool.submit(edit); var second = pool.submit(edit);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(uploads.get(id).metadataVersion()).isEqualTo(1);
    }

    @Test void deletionHidesPublicProjectionButRetainsFilesAndAudit() {
        UUID id = asset("READY");
        assertThat(service.checkDelete(id).deletable()).isTrue();
        assertThat(db.count("select count(*) from media_public where id = ?", id)).isEqualTo(1);
        var result = service.delete(id, 0, "operator");
        assertThat(result.filesRetained()).isTrue();
        assertThat(uploads.get(id).status()).isEqualTo("DELETED");
        assertThat(uploads.get(id).manifest()).isNull();
        assertThat(uploads.list(null, null, 50)).isEmpty();
        assertThat(db.count("select count(*) from media_public where id = ?", id)).isZero();
        assertThat(db.text("select source_key from media_assets where id = ?", id)).isEqualTo("private/test.mp4");
        assertThatThrownBy(() -> uploads.complete(id, "upload-test")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> uploads.retry(id)).isInstanceOf(IllegalStateException.class);
        verify(audit).log(eq("operator"), eq(AuditAction.DELETE), eq("MEDIA"), eq(id.toString()), eq(1), anyMap(), anyMap());
    }

    @Test void rejectsLiveBindingsAndDraftArticleReferences() {
        UUID id = asset("READY");
        db.jdbc.update("insert into media_presentations (placement_key, asset_id, presentation, enabled) values ('home', ?, '{}'::jsonb, true)", id);
        assertThat(service.checkDelete(id).references()).contains("placement:home");
        assertThatThrownBy(() -> service.delete(id, 0, "operator")).isInstanceOf(IllegalStateException.class);
        db.jdbc.update("update media_presentations set enabled = false");
        db.jdbc.update("insert into \"Projects\"(id,content) values ('draft-project', ?)", "<figure data-media-id=\"" + id + "\"></figure>");
        assertThat(service.checkDelete(id).references()).contains("project:draft-project");
        assertThatThrownBy(() -> service.delete(id, 0, "operator")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audit);
    }

    @Test void rejectsUploadsProcessingAndActiveJobs() {
        for (String status : new String[]{"UPLOADING", "PROCESSING"}) {
            UUID id = asset(status);
            assertThatThrownBy(() -> service.delete(id, 0, "operator")).isInstanceOf(IllegalStateException.class);
        }
        UUID id = asset("FAILED");
        db.jdbc.update("""
                insert into media_jobs(id,asset_id,source_version,profile_version,job_type,rendition,status)
                values (?, ?, 1, 'test', 'PLAN', 'plan', 'PENDING')
                """, UUID.randomUUID(), id);
        assertThat(service.checkDelete(id).activeJobs()).isTrue();
        assertThatThrownBy(() -> service.delete(id, 0, "operator")).isInstanceOf(IllegalStateException.class);
    }

    @Test void auditFailureRollsBackDeletion() {
        UUID id = asset("READY");
        when(audit.log(anyString(), any(), eq("MEDIA"), anyString(), anyInt(), anyMap(), anyMap()))
                .thenThrow(new IllegalStateException("audit unavailable"));
        assertThatThrownBy(() -> service.delete(id, 0, "operator")).isInstanceOf(IllegalStateException.class);
        assertThat(uploads.get(id).status()).isEqualTo("READY");
        assertThat(uploads.get(id).metadataVersion()).isZero();
    }

    @Test void rejectsMissingVersionAndInvalidMetadata() {
        UUID id = asset("READY");
        for (var request : java.util.List.of(new MediaManagementService.Metadata(null, "Title", ""),
                new MediaManagementService.Metadata(-1, "Title", ""), new MediaManagementService.Metadata(0, " ", ""),
                new MediaManagementService.Metadata(0, "Title", "x".repeat(2001))))
            assertThatThrownBy(() -> service.update(id, request, "operator")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(audit);
    }

    @Test void authenticatedHttpCrudRoundTripPersistsToDatabase() throws Exception {
        UUID id = asset("READY");
        var verifier = mock(SupabaseTokenVerifier.class);
        var users = mock(AdminUserService.class);
        when(verifier.isConfigured()).thenReturn(true);
        when(verifier.verify("admin-test")).thenReturn(Map.of("email", "admin@example.test"));
        when(verifier.verify("editor-test")).thenReturn(Map.of("email", "editor@example.test"));
        when(users.authorize(eq("admin@example.test"), anyList()))
                .thenReturn(AdminUserService.Authorization.allowed(AdminUserRole.ADMIN, "test", false));
        when(users.authorize(eq("editor@example.test"), anyList()))
                .thenReturn(AdminUserService.Authorization.allowed(AdminUserRole.EDITOR, "test", false));
        var http = MockMvcBuilders.standaloneSetup(new MediaAdminController(uploads, service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new AdminAuthFilter("", verifier, "", users)).build();
        String path = "/api/admin/media/" + id;
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (String method : java.util.List.of("GET", "PUT", "DELETE", "POST"))
            http.perform(request(org.springframework.http.HttpMethod.valueOf(method), path)
                    .header("Authorization", "Bearer editor-test"))
                    .andExpect(status().isForbidden());
        http.perform(put(path).header("Authorization", "Bearer admin-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"title\":\"HTTP title\",\"description\":\"HTTP description\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metadataVersion").value(1));
        http.perform(get(path).header("Authorization", "Bearer admin-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("HTTP title"))
                .andExpect(jsonPath("$.description").value("HTTP description"))
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"));
        http.perform(delete(path).header("Authorization", "Bearer admin-test"))
                .andExpect(status().isBadRequest());
        http.perform(delete(path).param("expectedVersion", "0").header("Authorization", "Bearer admin-test"))
                .andExpect(status().isConflict());
        http.perform(get(path + "/deletion-check").header("Authorization", "Bearer admin-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deletable").value(true));
        http.perform(delete(path).param("expectedVersion", "1").header("Authorization", "Bearer admin-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.filesRetained").value(true));
        http.perform(get("/api/admin/media").header("Authorization", "Bearer admin-test"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThat(uploads.get(id).status()).isEqualTo("DELETED");
        assertThat(db.count("select count(*) from media_public where id = ?", id)).isZero();
        verify(audit).log(eq("admin@example.test"), eq(AuditAction.DELETE), eq("MEDIA"), eq(id.toString()), eq(2), anyMap(), anyMap());
    }

    private UUID asset(String status) {
        UUID id = UUID.randomUUID();
        db.jdbc.update("""
                insert into media_assets(id,kind,status,title,original_filename,content_type,size_bytes,
                    source_version,source_key,profile_version,manifest,upload_id,created_by)
                values (?, 'VIDEO', ?, 'Original', 'test.mp4', 'video/mp4', 123, 1, 'private/test.mp4', 'test',
                    '{"url":"https://public.example/video.mp4"}'::jsonb, 'upload-test', 'test-admin')
                """, id, status);
        return id;
    }
}
