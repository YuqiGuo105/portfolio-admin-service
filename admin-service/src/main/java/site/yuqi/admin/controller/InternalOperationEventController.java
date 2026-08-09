package site.yuqi.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.operations.OperationEvent;
import site.yuqi.admin.operations.OperationEventPublisher;

import java.util.Map;

/** Service-to-service telemetry ingest. Domain APIs remain on their own auth boundary. */
@RestController
@RequestMapping("/api/internal/operations")
@RequiredArgsConstructor
public class InternalOperationEventController {

    private final OperationEventPublisher publisher;

    @PostMapping("/events")
    public ResponseEntity<?> ingest(@RequestBody OperationEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()
                || event.eventType() == null || event.eventType().isBlank()
                || event.sourceService() == null || event.sourceService().isBlank()
                || event.occurredAt() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "eventId, eventType, sourceService and occurredAt are required"));
        }
        publisher.publishExternal(event);
        return ResponseEntity.accepted().body(Map.of("eventId", event.eventId()));
    }

}
