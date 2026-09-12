package site.yuqi.admin.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import site.yuqi.admin.controller.KnowledgeAdminController;
import site.yuqi.admin.controller.GlobalExceptionHandler;
import site.yuqi.admin.security.AdminAuthFilter;
import site.yuqi.admin.service.AdminUserService;
import site.yuqi.admin.service.AuditLogService;
import site.yuqi.admin.service.IndexingJobService;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in local PostgreSQL integration, never a production or Supabase connection. */
@EnabledIfEnvironmentVariable(named = "KNOWLEDGE_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-z_]+_kb_test")
class KnowledgePostgresTest {
    JdbcTemplate jdbc;
    KnowledgeRepository repo;
    KnowledgeService service;
    TransactionTemplate tx;
    AuditLogService audit;
    MockMvc mvc;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final String actor = "local-test-admin";

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(System.getenv("KNOWLEDGE_TEST_JDBC_URL"), "alerts_test", "");
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("create table if not exists kb_documents(id uuid primary key default gen_random_uuid(),content text,metadata jsonb,embedding text,created_at timestamptz not null default now())");
        jdbc.execute("create table if not exists indexing_jobs(id uuid primary key default gen_random_uuid(),source_type text,source_id_text text,source_version int,status text,retry_count int default 0,last_error text,created_at timestamptz default now())");
        jdbc.execute("truncate kb_documents,indexing_jobs");
        repo = new KnowledgeRepository(jdbc, mapper); audit = mock(AuditLogService.class);
        var jobs = mock(IndexingJobService.class);
        when(jobs.enqueueKnowledge(anyString(), anyInt())).thenAnswer(call -> {
            jdbc.update("insert into indexing_jobs(source_type,source_id_text,source_version,status) values ('OWNER_QA',?,?,'PENDING')", call.getArgument(0, String.class), call.getArgument(1, Integer.class));
            return null;
        });
        service = new KnowledgeService(repo, audit, jobs, mock(KnowledgeIndexDispatcher.class));
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeAdminController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new AdminAuthFilter("local-test-secret", "", "", mock(AdminUserService.class))).build();
    }
    KnowledgeMutation draft(String text) { return new KnowledgeMutation("Test knowledge", "Example question", text, "DRAFT", "private", null); }
    Map<String,Object> create() { return tx.execute(s -> service.create(draft("Synthetic answer"), "create-key-12345", actor)); }
    UUID id(Map<String,Object> view) { return (UUID)view.get("id"); }
    String revision(Map<String,Object> view) { return (String)view.get("revision"); }

    @Test void createReadSearchAndIdempotentReplay() {
        var first = create();
        var again = create();
        assertThat(again.get("id")).isEqualTo(first.get("id"));
        assertThat(jdbc.queryForObject("select count(*) from kb_documents", Integer.class)).isOne();
        assertThat(service.list("Synthetic", "OWNED", "DRAFT", 25, 0).get("total")).isEqualTo(1L);
        assertThat(service.list("%' or 1=1 --", "ALL", "ALL", 25, 0).get("total")).isEqualTo(0L);
        assertThat(service.get(id(first))).containsEntry("content", "Synthetic answer");
    }
    @Test void rejectsKeyReuseWithDifferentBody() {
        create();
        assertThatThrownBy(() -> tx.execute(s -> service.create(draft("Different answer"), "create-key-12345", actor))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select content from kb_documents", String.class)).isEqualTo("Synthetic answer");
    }
    @Test void updateInvalidatesDerivedVectorsAndEnqueuesDurably() {
        var first = create(); UUID id = id(first);
        jdbc.update("insert into kb_documents(content,metadata,embedding) values ('obsolete',jsonb_build_object('source_id',?::text,'source_type','OWNER_QA','status','ACTIVE'),'old-vector')", id.toString());
        var updated = tx.execute(s -> service.update(id, new KnowledgeMutation("Updated", "", "New answer", "ACTIVE", "public", revision(first)), actor));
        assertThat(updated.get("revision")).isNotEqualTo(first.get("revision"));
        assertThat(jdbc.queryForObject("select count(*) from indexing_jobs where status='PENDING'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select metadata->>'status' from kb_documents where content='obsolete'", String.class)).isEqualTo("SUPERSEDED");
        assertThatThrownBy(() -> tx.execute(s -> service.update(id, new KnowledgeMutation("Stale", "", "Oops", "DRAFT", "private", revision(first)), actor))).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(id)).containsEntry("content", "New answer");
    }
    @Test void auditFailureRollsBackContentAndJob() {
        var first = create(); UUID id = id(first);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).log(anyString(), any(), anyString(), anyString(), any(), anyMap(), anyMap());
        assertThatThrownBy(() -> tx.execute(s -> service.update(id, new KnowledgeMutation("Updated", "", "New answer", "ACTIVE", "public", revision(first)), actor))).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(id)).containsEntry("content", "Synthetic answer");
        assertThat(jdbc.queryForObject("select count(*) from indexing_jobs", Integer.class)).isZero();
    }
    @Test void deleteRemovesOriginalAndDerivedButNotOtherRecords() {
        var first = create(); UUID id = id(first);
        jdbc.update("insert into kb_documents(content,metadata) values ('derived',jsonb_build_object('source_id',?::text,'source_type','PROFILE')),('unrelated','{}')", id.toString());
        tx.execute(s -> service.delete(id, revision(first), actor));
        assertThat(jdbc.queryForList("select content from kb_documents", String.class)).containsExactly("unrelated");
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NoSuchElementException.class);
    }
    @Test void legacyNotesAreEditableButGeneratedRecordsAreNot() {
        jdbc.update("insert into kb_documents(content,metadata) values ('legacy','{\"type\":\"chat_qa\"}'),('indexed','{\"source_type\":\"BLOG\"}'),('import','{\"source\":\"Blogs\"}')");
        assertThat(service.list("", "OWNED", "ALL", 25, 0).get("total")).isEqualTo(1L);
        assertThat(service.list("", "INDEXED", "ALL", 25, 0).get("total")).isEqualTo(2L);
    }
    @Test void apiRequiresAuthenticationAndReturnsValidationErrors() throws Exception {
        mvc.perform(get("/api/admin/knowledge")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/knowledge").header("X-Admin-Secret", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/knowledge").header("X-Admin-Secret", "local-test-secret"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store, max-age=0"));
        mvc.perform(get("/api/admin/knowledge/not-a-uuid").header("X-Admin-Secret", "local-test-secret")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/knowledge").header("X-Admin-Secret", "local-test-secret").contentType("application/json").content("not json")).andExpect(status().isBadRequest());
    }
    @Test void apiCrudRoundTrip() throws Exception {
        String response = mvc.perform(post("/api/admin/knowledge").header("X-Admin-Secret", "local-test-secret").header("Idempotency-Key", "http-create-12345")
                .contentType("application/json").content(mapper.writeValueAsString(draft("HTTP synthetic answer"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var row = mapper.readTree(response); String path = "/api/admin/knowledge/" + row.get("id").asText();
        mvc.perform(get(path).header("X-Admin-Secret", "local-test-secret")).andExpect(status().isOk()).andExpect(jsonPath("content").value("HTTP synthetic answer"));
        mvc.perform(delete(path).param("expectedRevision", row.get("revision").asText()).header("X-Admin-Secret", "local-test-secret")).andExpect(status().isOk()).andExpect(jsonPath("deleted").value(true));
        mvc.perform(get(path).header("X-Admin-Secret", "local-test-secret")).andExpect(status().isNotFound());
    }

    @Test void boundedBatchReportsMissingIdsAndDeduplicates() throws Exception {
        var first = create(); UUID id = id(first); UUID missing = UUID.randomUUID();
        var batch = service.getBatch(List.of(id, missing, id));
        assertThat((List<?>)batch.get("items")).hasSize(2);
        assertThat(batch.get("batch")).isEqualTo(Map.of("requested", 2, "found", 1, "maxSize", 25));
        assertThatThrownBy(() -> service.getBatch(Collections.nCopies(26, id))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getBatch(List.of())).isInstanceOf(IllegalArgumentException.class);
        mvc.perform(post("/api/admin/knowledge/search").header("X-Admin-Secret", "local-test-secret").contentType("application/json")
                .content("{\"filter\":{\"query\":\"Synthetic\"},\"page\":{\"size\":1,\"offset\":0}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("total").value(1));
        mvc.perform(post("/api/admin/knowledge/batch-get").header("X-Admin-Secret", "local-test-secret").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("ids", List.of(id, missing)))))
                .andExpect(status().isOk()).andExpect(jsonPath("items[0].found").value(true)).andExpect(jsonPath("items[1].found").value(false));
    }
}
