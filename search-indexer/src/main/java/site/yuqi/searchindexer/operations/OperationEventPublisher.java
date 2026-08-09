package site.yuqi.searchindexer.operations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import site.yuqi.searchindexer.events.ContentIndexEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationEventPublisher {
    private final KafkaTemplate<String, Object> kafka;

    @Value("${portfolio.kafka.topics.operations:platform.operation.events.v1}")
    private String topic;
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
        kafka.send(topic, correlationId, event).whenComplete((ignored, error) -> {
            if (error != null) log.warn("Failed to publish operation event {}: {}", eventType, error.getMessage());
        });
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
