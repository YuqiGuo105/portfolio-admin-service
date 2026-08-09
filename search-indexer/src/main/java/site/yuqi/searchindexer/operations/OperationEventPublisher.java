package site.yuqi.searchindexer.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import site.yuqi.searchindexer.events.ContentIndexEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationEventPublisher {
    private static final HttpClient HTTP = HttpClient.newBuilder().build();
    private final ObjectMapper objectMapper;

    @Value("${portfolio.operations.ingest-url:}")
    private String ingestUrl;
    @Value("${portfolio.operations.internal-token:}")
    private String internalToken;
    @Value("${portfolio.operations.timeout-ms:750}")
    private long timeoutMs;
    @Value("${spring.application.name:portfolio-search-indexer}")
    private String service;
    @Value("${portfolio.environment:production}")
    private String environment;

    public void publish(ContentIndexEvent source, String eventType, String status, long startedNanos,
                        Map<String, Object> attributes) {
        long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        String correlationId = nonBlank(source.getCorrelationId(), source.getIdempotencyKey());
        OperationEvent event = new OperationEvent(UUID.randomUUID().toString(), eventType, 1, Instant.now(),
                environment, source.getTraceId(), null, null, correlationId, source.getEventId(),
                source.getIdempotencyKey(), new OperationEvent.Actor("SERVICE", service),
                new OperationEvent.Subject(source.getSourceType(), source.getSourceId(), source.getSourceVersion()),
                service, status, 1, durationMs, attributes == null ? Map.of() : Map.copyOf(attributes));
        send(event);
    }

    private void send(OperationEvent event) {
        if (ingestUrl == null || ingestUrl.isBlank() || internalToken == null || internalToken.isBlank()) return;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ingestUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(event)))
                    .build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                if (error != null || response.statusCode() >= 300) {
                    log.warn("Operation event ingest failed type={} status={}", event.eventType(),
                            error == null ? response.statusCode() : error.getClass().getSimpleName());
                }
            });
        } catch (Exception error) {
            log.warn("Operation event ingest failed type={}: {}", event.eventType(), error.getMessage());
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
