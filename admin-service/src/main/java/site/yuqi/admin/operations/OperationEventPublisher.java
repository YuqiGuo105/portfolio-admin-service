package site.yuqi.admin.operations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

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

    @Value("${spring.application.name:portfolio-admin-service}")
    private String service;

    @Value("${portfolio.environment:production}")
    private String environment;

    public OperationEvent publish(String eventType, String status, String subjectType, String subjectId,
                                  Integer version, String causationId, String idempotencyKey,
                                  Map<String, Object> attributes) {
        OperationContext context = OperationContext.current();
        return publishWithContext(context.traceId(), context.correlationId(), context.actorId(),
                eventType, status, subjectType, subjectId, version, causationId, idempotencyKey, attributes);
    }

    public OperationEvent publishWithContext(String traceId, String correlationId, String actorId,
                                             String eventType, String status, String subjectType, String subjectId,
                                             Integer version, String causationId, String idempotencyKey,
                                             Map<String, Object> attributes) {
        OperationEvent event = new OperationEvent(
                UUID.randomUUID().toString(), eventType, 1, Instant.now(), environment,
                traceId, null, null, correlationId, causationId,
                idempotencyKey,
                new OperationEvent.Actor("system".equals(actorId) ? "SERVICE" : "USER", actorId),
                new OperationEvent.Subject(subjectType, subjectId, version),
                service, status, 1, null, attributes == null ? Map.of() : Map.copyOf(attributes));
        kafka.send(topic, correlationId, event).whenComplete((result, error) -> {
            if (error != null) {
                log.warn("Operation event projection failed type={} eventId={}: {}",
                        eventType, event.eventId(), error.getMessage());
            }
        });
        return event;
    }

    public void publishExternal(OperationEvent event) {
        String key = event.correlationId() == null || event.correlationId().isBlank()
                ? event.eventId()
                : event.correlationId();
        kafka.send(topic, key, event).whenComplete((result, error) -> {
            if (error != null) {
                log.warn("External operation event projection failed type={} eventId={}: {}",
                        event.eventType(), event.eventId(), error.getMessage());
            }
        });
    }
}
