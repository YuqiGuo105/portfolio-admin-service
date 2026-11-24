package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import site.yuqi.admin.controller.GlobalExceptionHandler;
import site.yuqi.admin.domain.AdminUserRole;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.security.AdminAuthFilter;
import site.yuqi.admin.security.SupabaseTokenVerifier;
import site.yuqi.admin.service.AdminUserService;
import site.yuqi.admin.service.AuditLogService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL transactions and signed HTTP requests to a loopback-only in-memory bucket. */
@EnabledIfEnvironmentVariable(named = MediaTestSchema.ENV, matches = MediaTestSchema.LOCAL_ONLY)
class MediaOriginalDeletionTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    MediaTestSchema db;
    FakeS3 storage;
    MediaAssetStore assets;
    OriginalsStore originals;
    MediaUploadService uploads;
    AuditLogService audit;
    MediaOriginalDeletionService service;

    @BeforeEach void setUp() throws Exception {
        db = new MediaTestSchema();
        storage = new FakeS3();
        assets = new MediaAssetStore(db.jdbc);
        var properties = OriginalsStoreDeletionTest.properties(storage.endpoint());
        originals = new OriginalsStore(properties);
        uploads = new MediaUploadService(assets, originals, properties, mock(MediaOutboxRelay.class), db.transactions, mapper);
        audit = mock(AuditLogService.class);
        service = service();
    }

    MediaOriginalDeletionService service() {
        return new MediaOriginalDeletionService(assets, originals, uploads, audit, mapper, db.transactions);
    }

    @AfterEach void close() { storage.close(); db.close(); }

    @Test void removesOnlyOriginalOfAnInUseReadyVideoAndPreservesPublicPlayback() throws Exception {
        UUID id = asset("VIDEO", "READY");
        db.jdbc.update("""
                insert into media_presentations(placement_key, asset_id, presentation, enabled)
                values ('yuqi-intro', ?, '{"id":"yuqi-intro","title":"Introduction","href":"/","contentId":"blog:1"}'::jsonb, true)
                """, id);
        db.jdbc.execute("create table \"Blogs\" (id text, content text)");
        db.jdbc.update("insert into \"Blogs\" values ('1', ?)", "<figure data-media-id=\"" + id + "\"></figure>");
        var before = uploads.get(id);
        String publicRow = db.text("select row_to_json(p)::text from media_public p where id = ?", id);
        String catalog = db.text("select row_to_json(p)::text from media_demo_catalog p where asset_id = ?", id);
        AtomicBoolean durable = new AtomicBoolean();
        storage.beforeDelete = key -> durable.set(db.count(
                "select count(*) from media_original_deletions where asset_id = ?", id) == 1);
        assertThat(service.check(id).deletable()).isTrue();
        var deleted = service.delete(id, 0, "admin");
        assertThat(durable).isTrue();
        assertThat(deleted.originalStatus()).isEqualTo("DELETED");
        assertThat(deleted.originalDeletedAt()).isNotNull();
        assertThat(deleted.metadataVersion()).isEqualTo(2);
        assertThat(deleted.status()).isEqualTo("READY");
        assertThat(deleted.deletedAt()).isNull();
        assertThat(deleted.manifest()).isEqualTo(before.manifest());
        assertThat(deleted.usages()).isEqualTo(before.usages()).isNotEmpty();
        assertThat(db.text("select row_to_json(p)::text from media_public p where id = ?", id)).isEqualTo(publicRow);
        assertThat(db.text("select row_to_json(p)::text from media_demo_catalog p where asset_id = ?", id)).isEqualTo(catalog);
        assertThat(db.count("select count(*) from \"Blogs\" where content like ?", "%" + id + "%")).isEqualTo(1);
        assertThat(storage.objects).containsOnlyKeys(publicPath(id));
        assertThat(db.count("select count(*) from media_event_outbox")).isZero();
        verify(audit).log(eq("admin"), eq(AuditAction.UPDATE), eq("MEDIA"), eq(id.toString()), eq(1), anyMap(),
                eq(Map.of("operation", "ORIGINAL_DELETE", "originalStatus", "DELETING", "renditionsRetained", true)));
        verify(audit).log(eq("admin"), eq(AuditAction.DELETE), eq("MEDIA"), eq(id.toString()), eq(2), anyMap(),
                eq(Map.of("operation", "ORIGINAL_DELETE", "originalStatus", "DELETED", "renditionsRetained", true)));
        assertThat(service.check(id).reasons()).contains("original_deleted");
        assertThatThrownBy(() -> uploads.retry(id)).hasMessageContaining("original is unavailable");
    }

    @Test void blocksNonVideoNonReadyAndActiveWorkBeforeStorageOrIntent() {
        for (String status : List.of("UPLOADING", "PROCESSING", "FAILED")) {
            UUID id = asset("VIDEO", status);
            assertThat(service.check(id).reasons()).contains("not_ready");
            assertThatThrownBy(() -> service.delete(id, 0, "admin")).isInstanceOf(IllegalStateException.class);
        }
        UUID image = asset("IMAGE", "READY");
        assertThat(service.check(image).reasons()).contains("not_video");
        assertThatThrownBy(() -> service.delete(image, 0, "admin")).isInstanceOf(IllegalStateException.class);
        for (String state : List.of("PENDING", "RUNNING")) {
            UUID id = asset("VIDEO", "READY");
            job(id, state);
            assertThat(service.check(id).reasons()).contains("active_jobs");
            assertThatThrownBy(() -> service.delete(id, 0, "admin")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(storage.requests).isEmpty();
        assertThat(db.count("select count(*) from media_original_deletions")).isZero();
    }

    @Test void validatesManifestAndExactSourceBindingAndRejectsStaleMetadata() {
        UUID id = asset("VIDEO", "READY");
        assertThatThrownBy(() -> service.delete(id, 7, "admin")).hasMessageContaining("Stale");
        String valid = db.text("select manifest::text from media_assets where id = ?", id);
        for (String invalid : List.of("{}", "null", valid.replace(id.toString(), UUID.randomUUID().toString()),
                valid.replace("master.m3u8", "../master.m3u8"), valid.replace("720", "0"))) {
            db.jdbc.update("update media_assets set manifest = ?::jsonb where id = ?", invalid, id);
            assertThat(service.check(id).reasons()).contains("invalid_playable_manifest");
            assertThatThrownBy(() -> service.delete(id, 0, "admin")).isInstanceOf(IllegalStateException.class);
        }
        db.jdbc.update("update media_assets set manifest = ?::jsonb where id = ?", valid, id);
        for (String invalid : List.of(OriginalsStoreDeletionTest.key(UUID.randomUUID()), "media/" + id + "/original.mp4",
                OriginalsStoreDeletionTest.key(id).replace("clip.mp4", "other.mp4"))) {
            db.jdbc.update("update media_assets set source_key = ? where id = ?", invalid, id);
            assertThat(service.check(id).reasons()).contains("original_storage_unverified");
            assertThatThrownBy(() -> service.delete(id, 0, "admin")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(storage.requests).isEmpty();
        assertThat(db.count("select count(*) from media_original_deletions")).isZero();
    }

    @Test void storageFailureLeavesRetryableIntentAndManualRetryUsesCurrentOrOriginalVersion() {
        UUID id = asset("VIDEO", "READY");
        storage.deleteStatus = 503;
        assertThatThrownBy(() -> service.delete(id, 0, "admin"))
                .isInstanceOf(MediaOriginalDeletionService.StorageDeletionException.class);
        var failed = uploads.get(id);
        assertThat(failed.originalStatus()).isEqualTo("DELETE_FAILED");
        assertThat(failed.originalDeletedAt()).isNull();
        assertThat(failed.status()).isEqualTo("READY");
        assertThat(storage.objects).containsKey(originalPath(id));
        assertThat(service.check(id).deletable()).isTrue();
        assertThat(job(id, "PENDING")).isZero();
        storage.deleteStatus = 204;
        var result = service().delete(id, failed.metadataVersion(), "retry-admin");
        assertThat(result.originalStatus()).isEqualTo("DELETED");
        assertThat(service.delete(id, 0, "admin").originalDeletedAt()).isEqualTo(result.originalDeletedAt());
        assertThat(db.count("select count(*) from media_original_deletions where asset_id = ?", id)).isEqualTo(1);
        assertThat(deleteRequests()).isEqualTo(2);
        assertThatThrownBy(() -> service.delete(id, 1, "admin")).hasMessageContaining("Stale");
    }

    @Test void commitFailureAfterStorageSuccessIsRecoveredWithoutDeletingAnythingElse() {
        UUID id = asset("VIDEO", "READY");
        when(audit.log(anyString(), eq(AuditAction.DELETE), eq("MEDIA"), anyString(), anyInt(), anyMap(), anyMap()))
                .thenThrow(new IllegalStateException("simulated completion transaction failure"));
        assertThatThrownBy(() -> service.delete(id, 0, "admin")).hasMessageContaining("transaction failure");
        assertThat(uploads.get(id).originalStatus()).isEqualTo("DELETING");
        assertThat(uploads.get(id).originalDeletedAt()).isNull();
        assertThat(storage.objects).doesNotContainKey(originalPath(id));
        assertThat(job(id, "PENDING")).isZero();
        reset(audit);
        assertThat(service().delete(id, 0, "admin").originalStatus()).isEqualTo("DELETED");
        assertThat(deleteRequests()).isEqualTo(1);
        assertThat(storage.objects).containsOnlyKeys(publicPath(id));
    }

    @Test void initialAuditFailurePreventsAnyStorageDeletion() {
        UUID id = asset("VIDEO", "READY");
        when(audit.log(anyString(), any(), eq("MEDIA"), anyString(), anyInt(), anyMap(), anyMap()))
                .thenThrow(new IllegalStateException("audit unavailable"));
        assertThatThrownBy(() -> service.delete(id, 0, "admin")).hasMessageContaining("audit unavailable");
        assertThat(uploads.get(id).originalStatus()).isEqualTo("AVAILABLE");
        assertThat(db.count("select count(*) from media_original_deletions")).isZero();
        assertThat(deleteRequests()).isZero();
    }

    @Test void legacyCheckIsReadOnlyAndDeletePinsVerifiedReceiptBeforeSideEffects() {
        UUID id = legacyAsset();
        var before = assets.find(id).orElseThrow();
        assertThat(service.check(id).deletable()).isTrue();
        assertThat(assets.find(id).orElseThrow()).isEqualTo(before);
        assertThat(db.count("select count(*) from media_original_deletions")).isZero();
        verifyNoInteractions(audit);
        storage.beforeDelete = key -> {
            var bound = assets.find(id).orElseThrow();
            var intent = assets.originalDeletion(id).orElseThrow();
            assertThat(bound.originalStorageEndpoint()).isEqualTo(storage.endpoint());
            assertThat(bound.originalStorageBucket()).isEqualTo(OriginalsStoreDeletionTest.BUCKET);
            assertThat(bound.originalStorageGeneration()).isEqualTo("1");
            assertThat(intent.storageEndpoint()).isEqualTo(bound.originalStorageEndpoint());
            assertThat(intent.storageGeneration()).isEqualTo(bound.originalStorageGeneration());
            verify(audit).log(eq("admin"), eq(AuditAction.UPDATE), eq("MEDIA"), eq(id.toString()), eq(1), anyMap(),
                    eq(Map.of("operation", "ORIGINAL_BINDING", "receiptVerified", true, "sourceVersion", 1)));
        };
        assertThat(service.delete(id, 0, "admin").originalStatus()).isEqualTo("DELETED");
        assertThat(deleteRequests()).isEqualTo(1);
        assertThat(storage.objects).containsOnlyKeys(publicPath(id));
    }

    @Test void missingLegacyObjectNeverBecomesACompletedDeletion() {
        UUID id = legacyAsset();
        storage.objects.remove(originalPath(id));
        assertThat(service.check(id).reasons()).contains("original_storage_unverified");
        assertThatThrownBy(() -> service.delete(id, 0, "admin")).hasMessageContaining("original_storage_unverified");
        assertThat(uploads.get(id).originalStatus()).isEqualTo("AVAILABLE");
        assertThat(uploads.get(id).originalDeletedAt()).isNull();
        assertThat(assets.find(id).orElseThrow().originalStorageEndpoint()).isNull();
        assertThat(db.count("select count(*) from media_original_deletions")).isZero();
        assertThat(deleteRequests()).isZero();
        verifyNoInteractions(audit);
    }

    @Test void legacyReceiptMismatchesIncompleteReceiptsAndPartialBindingsFailClosed() {
        for (String change : List.of("source_etag = 'wrong'", "source_etag = null", "source_bytes = null",
                "source_bytes = 2", "source_bytes = 2, size_bytes = 2", "original_storage_generation = '1'")) {
            UUID id = legacyAsset();
            db.jdbc.update("update media_assets set " + change + " where id = ?", id);
            assertThat(service.check(id).reasons()).contains("original_storage_unverified");
            assertThatThrownBy(() -> service.delete(id, 0, "admin")).hasMessageContaining("original_storage_unverified");
            assertThat(uploads.get(id).originalStatus()).isEqualTo("AVAILABLE");
            assertThat(storage.objects).containsKey(originalPath(id));
        }
        UUID id = legacyAsset();
        storage.generation = null;
        assertThat(service.check(id).reasons()).contains("original_storage_unverified");
        assertThat(db.count("select count(*) from media_original_deletions")).isZero();
        assertThat(deleteRequests()).isZero();
    }

    @Test void legacyBindingAuditFailureRollsBackBindingAndIntent() {
        UUID id = legacyAsset();
        when(audit.log(anyString(), any(), eq("MEDIA"), anyString(), anyInt(), anyMap(), anyMap()))
                .thenThrow(new IllegalStateException("audit unavailable"));
        assertThatThrownBy(() -> service.delete(id, 0, "admin")).hasMessageContaining("audit unavailable");
        assertThat(assets.find(id).orElseThrow().originalStorageEndpoint()).isNull();
        assertThat(uploads.get(id).originalStatus()).isEqualTo("AVAILABLE");
        assertThat(db.count("select count(*) from media_original_deletions")).isZero();
        assertThat(deleteRequests()).isZero();
        assertThat(storage.objects).containsKey(originalPath(id));
    }

    @Test void uploadBindingAndPendingIntentRejectChangedEndpointOrBucketBeforeStorage() throws Exception {
        UUID id = asset("VIDEO", "READY");
        try (var wrongStorage = new FakeS3()) {
            var changed = new MediaOriginalDeletionService(assets,
                    new OriginalsStore(OriginalsStoreDeletionTest.properties(wrongStorage.endpoint())),
                    uploads, audit, mapper, db.transactions);
            assertThat(changed.check(id).reasons()).contains("original_storage_unverified");
            assertThatThrownBy(() -> changed.delete(id, 0, "admin")).hasMessageContaining("original_storage_unverified");
            assertThat(wrongStorage.requests).isEmpty();
            assertThat(storage.requests).isEmpty();
            storage.deleteStatus = 503;
            assertThatThrownBy(() -> service.delete(id, 0, "admin"))
                    .isInstanceOf(MediaOriginalDeletionService.StorageDeletionException.class);
            int requestCount = storage.requests.size();
            assertThatThrownBy(() -> changed.delete(id, 0, "admin")).hasMessageContaining("original_storage_unverified");
            assertThat(wrongStorage.requests).isEmpty();
            db.jdbc.update("update media_assets set original_storage_bucket = 'different-private-bucket' where id = ?", id);
            assertThat(service.check(id).reasons()).contains("original_storage_unverified");
            assertThatThrownBy(() -> service.delete(id, 0, "admin")).hasMessageContaining("original_storage_unverified");
            assertThat(storage.requests).hasSize(requestCount);
            db.jdbc.update("update media_assets set original_storage_bucket = ? where id = ?", OriginalsStoreDeletionTest.BUCKET, id);
            storage.deleteStatus = 204;
            assertThat(service.delete(id, 0, "admin").originalStatus()).isEqualTo("DELETED");
        }
    }

    @Test void unresolvedIntentBlocksSoftDeletionAndReopeningOldJobs() {
        for (String table : List.of("\"Projects\"", "\"Blogs\"", "life_blogs"))
            db.jdbc.execute("create table " + table + " (id text primary key, content text, image_url text)");
        var management = new MediaManagementService(assets, uploads, audit, db.transactions);
        for (String state : List.of("DELETING", "DELETE_FAILED")) {
            UUID id = asset("VIDEO", "READY");
            job(id, "DEAD");
            new TransactionTemplate(db.transactions).executeWithoutResult(transaction -> {
                assets.beginOriginalDeletion(assets.lock(id).orElseThrow(), "admin");
                assets.setOriginalStatus(id, state);
            });
            int version = uploads.get(id).metadataVersion();
            assertThat(management.checkDelete(id).deletable()).isFalse();
            assertThatThrownBy(() -> management.delete(id, version, "admin")).hasMessageContaining("Resolve the original deletion");
            assertThat(assets.softDelete(id, version)).isFalse();
            assertThatThrownBy(() -> db.jdbc.update("update media_assets set deleted_at = now() where id = ?", id))
                    .hasMessageContaining("media_original_deletion_not_tombstoned");
            assertThat(db.jdbc.update("update media_jobs set status = 'PENDING' where asset_id = ?", id)).isZero();
            assertThatThrownBy(() -> uploads.retry(id)).hasMessageContaining("original is unavailable");
            assertThat(service.delete(id, 0, "admin").originalStatus()).isEqualTo("DELETED");
        }
    }

    @Test void generationIsPinnedAcrossFailedDeletionAndServiceRestart() {
        UUID id = asset("VIDEO", "READY");
        storage.beforeDelete = key -> storage.generation = "2";
        assertThatThrownBy(() -> service.delete(id, 0, "admin"))
                .isInstanceOf(MediaOriginalDeletionService.StorageDeletionException.class);
        assertThat(assets.originalDeletion(id).orElseThrow().storageGeneration()).isEqualTo("1");
        assertThat(uploads.get(id).originalStatus()).isEqualTo("DELETE_FAILED");
        assertThat(storage.objects).containsKey(originalPath(id));
        assertThatThrownBy(() -> service().delete(id, 0, "admin"))
                .isInstanceOf(MediaOriginalDeletionService.StorageDeletionException.class);
        assertThat(deleteRequests()).isEqualTo(1);
        assertThat(storage.objects).containsKey(originalPath(id));
    }

    @Test void deletedCheckDoesNotDependOnStorageConfigurationAndAssetSoftDeletionStillWorks() {
        UUID id = asset("VIDEO", "READY");
        var result = service.delete(id, 0, "admin");
        var brokenStorage = mock(OriginalsStore.class);
        when(brokenStorage.deletionBucket()).thenThrow(new IllegalStateException("Configuration changed"));
        var readService = new MediaOriginalDeletionService(assets, brokenStorage, uploads, audit, mapper, db.transactions);
        var check = readService.check(id);
        assertThat(check.originalStatus()).isEqualTo("DELETED");
        assertThat(check.deletable()).isFalse();
        assertThat(check.reasons()).containsExactly("original_deleted");
        verifyNoInteractions(brokenStorage);
        assertThat(assets.softDelete(id, result.metadataVersion())).isTrue();
        assertThat(db.text("select status from media_assets where id = ?", id)).isEqualTo("READY");
        assertThat(uploads.get(id).status()).isEqualTo("DELETED");
        assertThat(uploads.get(id).originalStatus()).isEqualTo("DELETED");
    }

    @Test void duplicateDeletesAndJobInsertionRaceHaveOnePhysicalDeleteAndNoNewWork() throws Exception {
        UUID id = asset("VIDEO", "READY");
        CountDownLatch deleting = new CountDownLatch(1), release = new CountDownLatch(1);
        storage.beforeDelete = key -> { deleting.countDown(); await(release); };
        try (var pool = Executors.newFixedThreadPool(3)) {
            var first = pool.submit(() -> service.delete(id, 0, "admin"));
            assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> service.delete(id, 0, "admin"));
            var newWork = pool.submit(() -> job(id, "PENDING"));
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).originalStatus()).isEqualTo("DELETED");
            assertThat(second.get(10, TimeUnit.SECONDS).originalStatus()).isEqualTo("DELETED");
            assertThat(newWork.get(10, TimeUnit.SECONDS)).isZero();
        } finally { release.countDown(); }
        assertThat(deleteRequests()).isEqualTo(1);
        assertThat(assets.hasActiveJobs(id)).isFalse();
        assertThat(assets.reopenFailed(id, 1)).isFalse();
        assertThatThrownBy(() -> db.jdbc.update("update media_assets set status = 'PROCESSING' where id = ?", id))
                .hasMessageContaining("media_original_deletion_terminal_video");
    }

    @Test void aJobThatWinsTheAssetLockBlocksDeletion() throws Exception {
        UUID id = asset("VIDEO", "READY");
        CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var work = pool.submit(() -> new TransactionTemplate(db.transactions).execute(transaction -> {
                int count = job(id, "PENDING"); inserted.countDown(); await(release); return count;
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var deletion = pool.submit(() -> {
                assertThatThrownBy(() -> service.delete(id, 0, "admin")).hasMessageContaining("active_jobs");
            });
            release.countDown();
            assertThat(work.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            deletion.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(storage.requests).isEmpty();
        assertThat(uploads.get(id).originalStatus()).isEqualTo("AVAILABLE");
    }

    @Test void administratorHttpContractCoversChecksErrorsRetryAndFreshAssetView() throws Exception {
        UUID id = asset("VIDEO", "READY");
        MockMvc http = http();
        String path = "/api/admin/media/" + id;
        http.perform(get(path + "/original-deletion-check")).andExpect(status().isUnauthorized());
        http.perform(delete(path + "/original").param("expectedVersion", "0")).andExpect(status().isUnauthorized());
        for (String role : List.of("editor", "publisher")) {
            http.perform(get(path + "/original-deletion-check").header("Authorization", "Bearer " + role))
                    .andExpect(status().isForbidden());
            http.perform(delete(path + "/original").param("expectedVersion", "0").header("Authorization", "Bearer " + role))
                    .andExpect(status().isForbidden());
        }
        assertThat(storage.requests).isEmpty();
        http.perform(get(path + "/original-deletion-check").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deletable").value(true))
                .andExpect(jsonPath("$.originalStatus").value("AVAILABLE")).andExpect(jsonPath("$.reasons").isEmpty())
                .andExpect(jsonPath("$.metadataVersion").value(0)).andExpect(jsonPath("$.sourceKey").doesNotExist())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"));
        http.perform(delete(path + "/original").header("Authorization", "Bearer admin")).andExpect(status().isBadRequest());
        http.perform(delete(path + "/original").param("expectedVersion", "invalid").header("Authorization", "Bearer admin"))
                .andExpect(status().isBadRequest());
        http.perform(delete(path + "/original").param("expectedVersion", "-1").header("Authorization", "Bearer admin"))
                .andExpect(status().isBadRequest());
        http.perform(delete(path + "/original").param("expectedVersion", "8").header("Authorization", "Bearer admin"))
                .andExpect(status().isConflict());
        http.perform(get("/api/admin/media/" + UUID.randomUUID() + "/original-deletion-check")
                .header("Authorization", "Bearer admin")).andExpect(status().isNotFound());
        storage.deleteStatus = 503;
        http.perform(delete(path + "/original").param("expectedVersion", "0").header("Authorization", "Bearer admin"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("original_delete_failed"));
        http.perform(get(path + "/original-deletion-check").header("Authorization", "Bearer admin"))
                .andExpect(jsonPath("$.deletable").value(true)).andExpect(jsonPath("$.originalStatus").value("DELETE_FAILED"));
        storage.deleteStatus = 204;
        http.perform(delete(path + "/original").param("expectedVersion", "0").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.originalStatus").value("DELETED"))
                .andExpect(jsonPath("$.originalDeletedAt").isNotEmpty()).andExpect(jsonPath("$.manifest.hls").value("master.m3u8"))
                .andExpect(jsonPath("$.metadataVersion").value(4)).andExpect(jsonPath("$.sourceKey").doesNotExist());
    }

    private MockMvc http() {
        var verifier = mock(SupabaseTokenVerifier.class);
        var users = mock(AdminUserService.class);
        when(verifier.isConfigured()).thenReturn(true);
        for (String role : List.of("admin", "editor", "publisher")) {
            when(verifier.verify(role)).thenReturn(Map.of("email", role + "@example.test"));
            when(users.authorize(eq(role + "@example.test"), anyList())).thenReturn(
                    AdminUserService.Authorization.allowed(AdminUserRole.valueOf(role.toUpperCase()), "test", false));
        }
        return MockMvcBuilders.standaloneSetup(new MediaOriginalDeletionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new AdminAuthFilter("", verifier, "", users)).build();
    }

    private UUID asset(String kind, String status) {
        UUID id = UUID.randomUUID();
        String key = OriginalsStoreDeletionTest.key(id);
        String manifest = """
                {"version":1,"kind":"VIDEO","basePath":"media/%s/1/v1","hls":"master.m3u8",
                 "poster":{"path":"poster.jpg"},"mp4":{"path":"clip.mp4"},"durationSeconds":10,
                 "renditions":[{"playlist":"720/playlist.m3u8","width":1280,"height":720}]}
                """.formatted(id);
        db.jdbc.update("""
                insert into media_assets(id,kind,status,title,original_filename,content_type,size_bytes,source_version,
                    source_key,upload_key,upload_id,profile_version,manifest,created_by)
                values (?, ?, ?, 'Video', 'clip.mp4', 'video/mp4', 10, 1, ?, ?, '0123456789abcdef0123', 'v1', ?::jsonb, 'test')
                """, id, kind, status, key, key, manifest);
        db.jdbc.update("""
                update media_assets set original_storage_endpoint = ?, original_storage_bucket = ?, original_storage_generation = '1' where id = ?
                """, storage.endpoint(), OriginalsStoreDeletionTest.BUCKET, id);
        storage.objects.put(originalPath(id), new byte[]{1});
        storage.objects.put(publicPath(id), new byte[]{2});
        return id;
    }

    private UUID legacyAsset() {
        UUID id = asset("VIDEO", "READY");
        db.jdbc.update("""
                update media_assets set original_storage_endpoint = null, original_storage_bucket = null,
                    original_storage_generation = null, source_etag = 'joined', source_bytes = 1, size_bytes = 1 where id = ?
                """, id);
        return id;
    }

    private int job(UUID id, String status) {
        return db.jdbc.update("""
                insert into media_jobs(id,asset_id,source_version,profile_version,job_type,rendition,status)
                values (?, ?, 1, 'v1', 'PLAN', ?, ?)
                """, UUID.randomUUID(), id, UUID.randomUUID().toString().substring(0, 8), status);
    }

    private static String originalPath(UUID id) {
        return "/" + OriginalsStoreDeletionTest.BUCKET + "/" + OriginalsStoreDeletionTest.key(id);
    }

    private static String publicPath(UUID id) { return "/portfolio-media-public/media/" + id + "/1/v1/master.m3u8"; }
    private long deleteRequests() { return storage.requests.stream().filter(r -> r.method().equals("DELETE")).count(); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
