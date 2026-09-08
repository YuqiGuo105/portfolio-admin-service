package site.yuqi.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class McpOperationServiceTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    McpOperationService service;
    static final String HASH="a".repeat(64);
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String sql=new ClassPathResource("db/migration/V10__durable_mcp_operations.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String statement:sql.split(";")) {
            if(!statement.isBlank() && !statement.contains("row level security") && !statement.contains("revoke all")
                    && !statement.contains("create index"))
                jdbc.execute(statement.replace("timestamptz","timestamp"));
        }
        service=new McpOperationService(jdbc,new ObjectMapper());
    }
    Map<String,Object> claim() { return tx.execute(s -> service.claim("actor","test.write","intent-123",HASH)); }
    @Test void concurrentInstancesDispatchExactlyOnce() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);
            List<Future<Map<String,Object>>> futures=new ArrayList<>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> {start.await(); return claim();}));
            start.countDown();
            int dispatched=0;
            for(var f:futures) if(Boolean.TRUE.equals(f.get(10,TimeUnit.SECONDS).get("dispatch"))) dispatched++;
            assertThat(dispatched).isEqualTo(1);
        }
    }
    @Test void completionSurvivesNewServiceInstanceAndRejectsChangedPayload() {
        var c=claim(); UUID id=UUID.fromString((String)c.get("operationId"));
        tx.execute(s -> service.complete(id,"actor",UUID.fromString((String)c.get("leaseToken")),"SUCCEEDED",200,Map.of("version",7)));
        service=new McpOperationService(jdbc,new ObjectMapper());
        assertThat(claim()).containsEntry("dispatch",false).containsEntry("state","SUCCEEDED");
        assertThat(((Map<?,?>)claim().get("response")).get("version")).isEqualTo(7);
        assertThatThrownBy(() -> tx.execute(s -> service.claim("actor","test.write","intent-123","b".repeat(64))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");
    }
    @Test void expiredDispatchNeverExecutesAgain() {
        var c=claim(); UUID id=UUID.fromString((String)c.get("operationId"));
        jdbc.update("update mcp_operations set lease_until=? where operation_id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),id);
        assertThat(claim()).containsEntry("dispatch",false).containsEntry("state","UNKNOWN").containsEntry("safeToRetry",false);
    }
    @Test void retryableAdmissionRotatesLeaseAndRejectsStaleCompletion() {
        var first=claim(); UUID id=UUID.fromString((String)first.get("operationId"));
        UUID token=UUID.fromString((String)first.get("leaseToken"));
        tx.execute(s -> service.complete(id,"actor",token,"RETRYABLE",503,Map.of("code","bulkhead_full")));
        assertThat(claim()).containsEntry("dispatch",true).containsEntry("attempt",2);
        assertThatThrownBy(() -> tx.execute(s -> service.complete(id,"actor",token,"SUCCEEDED",200,Map.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STALE");
    }
    @Test void principalsAreIsolatedAndTransitionsAreDurable() {
        var c=claim(); UUID id=UUID.fromString((String)c.get("operationId"));
        assertThatThrownBy(() -> service.status(id,"other")).isInstanceOf(NoSuchElementException.class);
        assertThat((List<?>)service.timeline(id,"actor").get("transitions")).hasSize(1);
        Map<String,Object> other=tx.execute(s -> service.claim("other","test.write","intent-123",HASH));
        assertThat(other).containsEntry("dispatch",true);
    }
    @Test void missingStableKeyIsRejectedBeforeAdmission() {
        assertThatThrownBy(() -> tx.execute(s -> service.claim("actor","test.write","",HASH))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from mcp_operations",Integer.class)).isZero();
    }
}
