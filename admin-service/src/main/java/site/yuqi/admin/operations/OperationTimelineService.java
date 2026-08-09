package site.yuqi.admin.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.core.rest.RestStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OperationTimelineService {

    private static final String INDEX_PATTERN = "platform-operation-events-*";
    private final RestHighLevelClient openSearch;
    private final ObjectMapper objectMapper;

    public TimelineResponse find(String query, int requestedLimit) throws IOException {
        int limit = Math.min(Math.max(requestedLimit, 1), 500);
        if (query == null || query.isBlank()) {
            List<Map<String, Object>> latest = search(QueryBuilders.matchAllQuery(), limit, SortOrder.DESC);
            return response("", Set.of(), latest);
        }

        List<Map<String, Object>> seeds = search(identifierQuery(query), Math.min(limit, 100), SortOrder.DESC);
        Set<String> correlations = new LinkedHashSet<>();
        for (Map<String, Object> seed : seeds) {
            Object value = seed.get("correlationId");
            if (value != null) correlations.add(String.valueOf(value));
        }

        List<Map<String, Object>> events = correlations.isEmpty()
                ? seeds
                : search(QueryBuilders.termsQuery("correlationId.keyword", correlations), limit, SortOrder.ASC);
        return response(query, correlations, events);
    }

    private TimelineResponse response(String query, Set<String> correlations,
                                      List<Map<String, Object>> events) {
        long failures = events.stream().filter(event -> {
            String status = String.valueOf(event.getOrDefault("status", ""));
            return "FAILED".equalsIgnoreCase(status) || "DLQ".equalsIgnoreCase(status);
        }).count();
        long retries = events.stream().filter(event -> {
            Object attempt = event.get("attempt");
            return attempt instanceof Number number && number.intValue() > 1;
        }).count();
        long services = events.stream().map(event -> event.get("sourceService")).filter(java.util.Objects::nonNull)
                .distinct().count();
        return new TimelineResponse(query, correlations, events, new Summary(events.size(), services, failures, retries));
    }

    private BoolQueryBuilder identifierQuery(String query) {
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        if (query == null || query.isBlank()) return bool.must(QueryBuilders.matchAllQuery());
        for (String field : List.of("eventId.keyword", "runId.keyword", "correlationId.keyword",
                "causationId.keyword", "idempotencyKey.keyword", "subject.id.keyword",
                "attributes.sessionId.keyword", "attributes.contentId.keyword")) {
            bool.should(QueryBuilders.termQuery(field, query));
        }
        return bool.minimumShouldMatch(1);
    }

    private List<Map<String, Object>> search(org.opensearch.index.query.QueryBuilder query, int limit,
                                             SortOrder sortOrder)
            throws IOException {
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(query)
                .size(limit)
                .sort("occurredAt", sortOrder);
        SearchResponse response;
        try {
            response = openSearch.search(new SearchRequest(INDEX_PATTERN).source(source), RequestOptions.DEFAULT);
        } catch (OpenSearchStatusException e) {
            if (e.status() == RestStatus.NOT_FOUND) return List.of();
            throw e;
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            events.add(objectMapper.readValue(hit.getSourceAsString(), new TypeReference<>() { }));
        }
        return events;
    }

    public record TimelineResponse(String query, Set<String> correlationIds,
                                   List<Map<String, Object>> events, Summary summary) { }
    public record Summary(long events, long services, long failures, long retries) { }
}
