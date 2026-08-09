package site.yuqi.admin.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.indices.CreateIndexRequest;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
class OperationTimelineProjectorTest {

    @Test
    void createsOneShardIndexAndStableDocumentIdForReplays() throws Exception {
        OperationEvent event = new OperationEvent(
                "event-123", "test.completed", 1, Instant.parse("2026-08-09T17:00:00Z"),
                "test", "trace-123", null, "run-123", "correlation-123", null,
                "event-123", null, null, "test-service", "COMPLETED", 1, 12L, Map.of());
        byte[] payload = new ObjectMapper().findAndRegisterModules().writeValueAsBytes(event);

        CreateIndexRequest createRequest = OperationTimelineProjector.createIndexRequest();
        assertThat(createRequest.index()).isEqualTo("platform-operation-events-v1");
        assertThat(createRequest.settings().get("index.number_of_shards")).isEqualTo("1");
        assertThat(createRequest.settings().get("index.number_of_replicas")).isEqualTo("0");

        IndexRequest first = OperationTimelineProjector.indexRequest(event, payload);
        IndexRequest replay = OperationTimelineProjector.indexRequest(event, payload);
        assertThat(first.index()).isEqualTo("platform-operation-events-v1");
        assertThat(first.id()).isEqualTo("event-123");
        assertThat(replay.id()).isEqualTo(first.id());
    }
}
