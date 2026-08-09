package site.yuqi.admin.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.xcontent.XContentType;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Idempotent read-model projector. Replays overwrite the same event document. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationTimelineProjector {

    private static final DateTimeFormatter INDEX_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")
            .withZone(ZoneOffset.UTC);

    private final RestHighLevelClient openSearch;
    private final ObjectMapper objectMapper;

    public void project(OperationEvent event) throws Exception {
        String index = "platform-operation-events-" + INDEX_DATE.format(event.occurredAt());
        byte[] payload = objectMapper.writeValueAsBytes(event);
        IndexRequest request = new IndexRequest(index)
                .id(event.eventId())
                .source(payload, XContentType.JSON);
        openSearch.index(request, RequestOptions.DEFAULT);
        log.debug("Projected operation event {} to {}", event.eventId(), index);
    }
}
