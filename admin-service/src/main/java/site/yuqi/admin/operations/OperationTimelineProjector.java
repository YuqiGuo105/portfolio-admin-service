package site.yuqi.admin.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.xcontent.XContentType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Idempotent read-model projector. Kafka replay overwrites the same event document. */
@Slf4j
@Component
@ConditionalOnProperty(name = "portfolio.operations.projection.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OperationTimelineProjector {

    private static final DateTimeFormatter INDEX_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")
            .withZone(ZoneOffset.UTC);

    private final RestHighLevelClient openSearch;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${portfolio.kafka.topics.operations:platform.operation.events.v1}",
            groupId = "${portfolio.operations.consumer-group:portfolio-operation-timeline-v1}")
    public void onEvent(String payload, Acknowledgment acknowledgment) throws Exception {
        OperationEvent event = objectMapper.readValue(payload, OperationEvent.class);
        String index = "platform-operation-events-" + INDEX_DATE.format(event.occurredAt());
        IndexRequest request = new IndexRequest(index)
                .id(event.eventId())
                .source(payload, XContentType.JSON);
        openSearch.index(request, RequestOptions.DEFAULT);
        acknowledgment.acknowledge();
        log.debug("Projected operation event {} to {}", event.eventId(), index);
    }
}
