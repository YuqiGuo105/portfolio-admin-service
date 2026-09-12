package site.yuqi.ragindexer.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.yuqi.ragindexer.source.ContentFetcher;
import site.yuqi.ragindexer.source.RagSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Tests PostgreSQL provenance/transaction SQL; vector math is not exercised by this fixture. */
@EnabledIfEnvironmentVariable(named = "KNOWLEDGE_RAG_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-z_]+_kb_test")
class KnowledgeProjectionPostgresTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    ContentFetcher fetcher;
    KbDocumentWriter writer;
    UUID id;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(System.getenv("KNOWLEDGE_RAG_TEST_JDBC_URL"), "alerts_test", "");
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("do $$ begin if not exists(select 1 from pg_type where typname='vector') then create domain vector as text; end if; end $$");
        jdbc.execute("create table if not exists kb_documents(id uuid primary key default gen_random_uuid(),content text,metadata jsonb,embedding vector,created_at timestamptz default now())");
        jdbc.execute("truncate kb_documents");
        id = UUID.randomUUID();
        jdbc.update("insert into kb_documents(id,content,metadata) values (?,'Verified synthetic fact','{\"type\":\"chat_qa\",\"title\":\"Synthetic\",\"question\":\"Example?\",\"status\":\"ACTIVE\",\"answer_visibility\":\"public\",\"evidence_review\":\"approved\",\"management_version\":1}')", id);
        fetcher = new ContentFetcher(jdbc); writer = new KbDocumentWriter(jdbc, new ObjectMapper());
    }
    RagSource source() { return fetcher.fetch("OWNER_QA", id.toString()).orElseThrow(); }
    void index(RagSource source, int version) {
        tx.executeWithoutResult(s -> writer.supersedeAndInsert(source, version, List.of("Synthetic chunk"), List.of(vector())));
    }
    float[] vector() { float[] v = new float[1536]; v[0] = 1; return v; }
    int projections() { return jdbc.queryForObject("select count(*) from kb_documents where metadata->>'source_type'='OWNER_QA'", Integer.class); }

    @Test void approvedSourceProducesRetrievableProvenanceAndReplayDoesNotDuplicate() {
        index(source(), 1); index(source(), 1);
        assertThat(projections()).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from kb_documents k join kb_documents original on original.id::text=k.metadata->>'source_id'
                where k.metadata->>'status'='ACTIVE' and k.metadata->>'original_content_md5'=md5(original.content)
                and k.metadata->>'answer_visibility'='public' and k.metadata->>'source_requires_login'='true'
                and k.metadata->>'embedding_model'='gemini-embedding-001' and k.metadata->>'evidence_review'='approved'
                and k.metadata->>'retrieval_eligible'='true'
                """, Integer.class)).isOne();
    }
    @Test void updateDuringEmbeddingCannotWriteStaleAnswer() {
        RagSource stale = source();
        jdbc.update("update kb_documents set metadata=jsonb_set(metadata,'{management_version}','2'),content='Changed fact' where id=?", id);
        index(stale, 1); assertThat(projections()).isZero();
        index(source(), 2); assertThat(projections()).isOne();
        index(stale, 1); assertThat(projections()).isOne();
    }
    @Test void deleteDuringEmbeddingCannotResurrectRecord() {
        RagSource stale = source(); jdbc.update("delete from kb_documents where id=?", id);
        index(stale, 1); assertThat(projections()).isZero();
    }
    @Test void privateAndDraftSourcesAreNeverEmbedded() {
        RagSource stale = source();
        jdbc.update("update kb_documents set metadata=metadata || '{\"status\":\"DRAFT\",\"answer_visibility\":\"private\"}' where id=?", id);
        assertThat(fetcher.fetch("OWNER_QA", id.toString())).isEmpty();
        index(stale, 1); assertThat(projections()).isZero();
    }
    @Test void invalidVectorRollsBackWithoutDamagingCurrentProjection() {
        index(source(), 1);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> writer.supersedeAndInsert(source(), 1, List.of("Bad"), List.of(new float[2]))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(projections()).isOne();
    }
    @Test void lateDeleteEventDoesNotSupersedeNewActiveVersion() {
        index(source(), 1);
        tx.executeWithoutResult(s -> writer.supersedeAll("OWNER_QA", id.toString()));
        assertThat(jdbc.queryForObject("select count(*) from kb_documents where metadata->>'source_type'='OWNER_QA' and metadata->>'status'='ACTIVE'", Integer.class)).isOne();
    }
}
