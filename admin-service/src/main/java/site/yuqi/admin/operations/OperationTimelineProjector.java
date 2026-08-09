package site.yuqi.admin.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.rest.RestStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotent read-model projector. Replays overwrite the same event document. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationTimelineProjector {

    private static final String INDEX = "platform-operation-events-v1";

    private final RestHighLevelClient openSearch;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean indexReady = new AtomicBoolean();

    public void project(OperationEvent event) throws Exception {
        ensureIndex();
        byte[] payload = objectMapper.writeValueAsBytes(event);
        IndexRequest request = indexRequest(event, payload);
        openSearch.index(request, RequestOptions.DEFAULT);
        log.debug("Projected operation event {} to {}", event.eventId(), INDEX);
    }

    private void ensureIndex() throws IOException {
        if (indexReady.get()) return;
        synchronized (indexReady) {
            if (indexReady.get()) return;
            GetIndexRequest existsRequest = new GetIndexRequest(INDEX);
            if (!openSearch.indices().exists(existsRequest, RequestOptions.DEFAULT)) {
                CreateIndexRequest createRequest = createIndexRequest();
                try {
                    openSearch.indices().create(createRequest, RequestOptions.DEFAULT);
                } catch (OpenSearchStatusException race) {
                    if (race.status() != RestStatus.BAD_REQUEST) throw race;
                    if (!openSearch.indices().exists(existsRequest, RequestOptions.DEFAULT)) throw race;
                }
            }
            indexReady.set(true);
        }
    }

    static CreateIndexRequest createIndexRequest() {
        return new CreateIndexRequest(INDEX)
                .settings(Settings.builder()
                        .put("index.number_of_shards", 1)
                        .put("index.number_of_replicas", 0));
    }

    static IndexRequest indexRequest(OperationEvent event, byte[] payload) {
        return new IndexRequest(INDEX)
                .id(event.eventId())
                .source(payload, XContentType.JSON);
    }
}
