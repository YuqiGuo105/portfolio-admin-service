package site.yuqi.admin.mediapipeline;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import site.yuqi.admin.adapter.*;
import site.yuqi.admin.controller.GlobalExceptionHandler;
import site.yuqi.admin.domain.*;
import site.yuqi.admin.events.*;
import site.yuqi.admin.operations.OperationEventPublisher;
import site.yuqi.admin.security.*;
import site.yuqi.admin.service.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = MediaTestSchema.ENV, matches = MediaTestSchema.LOCAL_ONLY)
class MediaArticleTest {
    MediaTestSchema db;
    MediaArticleService articles;
    ContentService content;
    AuditLogService audit;
    OutboxService outbox;
    IndexingJobService jobs;
    MockMvc http;
    final UUID articleId = UUID.randomUUID();
    final String original = "<h2>Article</h2><pre class='mermaid'>A --&gt; B</pre>";

    @BeforeEach @SuppressWarnings("unchecked") void setUp() {
        db = new MediaTestSchema();
        db.jdbc.execute("create table article_fixture(id text primary key, content text, published boolean default false)");
        db.jdbc.update("insert into article_fixture(id,content) values (?,?)", articleId.toString(), original);
        ContentAdapter adapter = mock(ContentAdapter.class);
        when(adapter.get(anyString())).thenAnswer(inv -> Optional.of(read(inv.getArgument(0), false)));
        when(adapter.getForUpdate(anyString())).thenAnswer(inv -> Optional.of(read(inv.getArgument(0), true)));
        when(adapter.update(anyString(), anyMap())).thenAnswer(inv -> {
            Map<String, Object> input = inv.getArgument(1);
            db.jdbc.update("update article_fixture set content=? where id=?", input.get("content"), inv.getArgument(0));
            return read(inv.getArgument(0), false);
        });
        when(adapter.markPublished(anyString())).thenAnswer(inv -> {
            db.jdbc.update("update article_fixture set published=true where id=?", inv.getArgument(0, String.class));
            return read(inv.getArgument(0), false);
        });
        when(adapter.toSnapshot(any())).thenAnswer(inv -> Map.of("content", ((NormalizedContent) inv.getArgument(0)).getContent()));
        var registry = mock(ContentAdapterRegistry.class);
        when(registry.get(any())).thenReturn(adapter);
        var policy = new MediaArticlePolicy(new MediaAssetStore(db.jdbc));
        ObjectProvider<MediaArticlePolicy> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(policy);
        audit = mock(AuditLogService.class);
        outbox = mock(OutboxService.class);
        jobs = mock(IndexingJobService.class);
        content = transactional(new ContentService(registry, mock(VersionService.class), outbox, jobs, audit,
                mock(IndexEventPublisher.class), mock(NotificationEventPublisher.class), mock(OperationEventPublisher.class), provider));
        articles = transactional(new MediaArticleService(registry, content, policy));
        var verifier = mock(SupabaseTokenVerifier.class);
        var users = mock(AdminUserService.class);
        when(verifier.isConfigured()).thenReturn(true);
        for (var role : AdminUserRole.values()) {
            String email = role.name().toLowerCase(Locale.ROOT) + "@example.test";
            when(verifier.verify(role.name())).thenReturn(Map.of("email", email));
            when(users.authorize(eq(email), anyList())).thenReturn(AdminUserService.Authorization.allowed(role, "test", false));
        }
        http = MockMvcBuilders.standaloneSetup(new MediaArticleController(articles))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new AdminAuthFilter("", verifier, "", users)).build();
    }

    @AfterEach void close() { db.close(); }

    @Test void authenticatedArticleAttachReadDetachRoundTrip() throws Exception {
        UUID video = asset("VIDEO", "READY");
        String path = "/api/admin/media/articles/PROJECT/" + articleId;
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : List.of("EDITOR", "PUBLISHER")) {
            http.perform(get(path).header("Authorization", "Bearer " + role)).andExpect(status().isForbidden());
            http.perform(post(path).header("Authorization", "Bearer " + role)).andExpect(status().isForbidden());
            http.perform(delete(path).header("Authorization", "Bearer " + role)).andExpect(status().isForbidden());
        }
        String hash = articles.get("PROJECT", articleId).contentHash();
        String request = "{\"id\":\"" + video + "\",\"expectedContentHash\":\"" + hash + "\"}";
        http.perform(post(path).header("Authorization", "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mediaIds[0]").value(video.toString()))
                .andExpect(jsonPath("$.publicationChanged").value(false));
        assertThat(read(articleId.toString(), false).getContent()).contains(original, "data-media-kind=\"VIDEO\"");
        assertThat(db.count("select count(*) from article_fixture where published")).isZero();
        http.perform(post(path).header("Authorization", "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());
        http.perform(get(path).header("Authorization", "Bearer ADMIN")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"));
        http.perform(delete(path).header("Authorization", "Bearer ADMIN").param("id", video.toString())
                .param("expectedContentHash", articles.get("PROJECT", articleId).contentHash()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mediaIds").isEmpty());
        assertThat(read(articleId.toString(), false).getContent()).isEqualTo(original);
        verify(audit, times(2)).log(eq("admin@example.test"), eq(AuditAction.UPDATE), eq(SourceType.PROJECT),
                eq(articleId.toString()), anyInt(), anyMap(), anyMap());
        verifyNoInteractions(outbox, jobs);
    }

    @Test void unfinishedOrNonVideoAssetsAndInvalidSourceAreRejected() {
        String hash = articles.get("BLOG", articleId).contentHash();
        assertThatThrownBy(() -> articles.attach("PROJECT", articleId,
                new MediaArticleService.Attach(asset("VIDEO", "PROCESSING"), hash, "START"), "test"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> articles.attach("PROJECT", articleId,
                new MediaArticleService.Attach(asset("IMAGE", "READY"), hash, "START"), "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> articles.get("LIFE_BLOG", articleId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(read(articleId.toString(), false).getContent()).isEqualTo(original);
        verifyNoInteractions(audit, outbox, jobs);
    }

    @Test void auditFailureRollsBackArticleAndHash() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .log(anyString(), any(), any(SourceType.class), anyString(), anyInt(), anyMap(), anyMap());
        String hash = articles.get("PROJECT", articleId).contentHash();
        assertThatThrownBy(() -> articles.attach("PROJECT", articleId,
                new MediaArticleService.Attach(asset("VIDEO", "READY"), hash, "END"), "test"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(articles.get("PROJECT", articleId).contentHash()).isEqualTo(hash);
    }

    @Test void publicationWithUnreadyReferenceRollsBackBeforeOutboxAndIndexing() {
        UUID video = asset("VIDEO", "PROCESSING");
        db.jdbc.update("update article_fixture set content=?", "<FIGURE DATA-MEDIA-ID='" + video + "'></FIGURE>");
        assertThatThrownBy(() -> content.publish(SourceType.PROJECT, articleId.toString(), "test", "publish"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.count("select count(*) from article_fixture where published")).isZero();
        verifyNoInteractions(audit, outbox, jobs);
    }

    private NormalizedContent read(String id, boolean lock) {
        return db.jdbc.queryForObject("select content from article_fixture where id=?" + (lock ? " for update" : ""),
                (rs, index) -> NormalizedContent.builder().sourceType(SourceType.PROJECT).sourceId(id)
                        .title("Fixture").content(rs.getString(1)).build(), id);
    }

    @SuppressWarnings("unchecked") private <T> T transactional(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(db.transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private UUID asset(String kind, String status) {
        UUID id = UUID.randomUUID();
        db.jdbc.update("""
                insert into media_assets(id,kind,status,title,original_filename,content_type,size_bytes,
                    source_version,source_key,profile_version,manifest,created_by)
                values (?, ?, ?, 'Test', 'test.mp4', 'video/mp4', 123, 1, 'private/test.mp4', 'test',
                    '{"url":"https://public.example/test.mp4"}'::jsonb, 'test-admin')
                """, id, kind, status);
        return id;
    }
}
