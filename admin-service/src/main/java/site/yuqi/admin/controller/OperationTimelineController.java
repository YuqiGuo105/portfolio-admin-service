package site.yuqi.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.operations.OperationEvent;
import site.yuqi.admin.operations.OperationEventPublisher;
import site.yuqi.admin.operations.OperationTimelineService;

import java.io.IOException;

@RestController
@RequestMapping("/api/admin/operations/timeline")
@RequiredArgsConstructor
public class OperationTimelineController {

    private final OperationTimelineService timeline;
    private final OperationEventPublisher publisher;

    @GetMapping
    public ResponseEntity<?> find(@RequestParam(name = "q", defaultValue = "") String query,
                                  @RequestParam(defaultValue = "250") int limit) throws IOException {
        return ResponseEntity.ok(timeline.find(query, limit));
    }

    @PostMapping("/events")
    public ResponseEntity<?> ingest(@RequestBody OperationEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()
                || event.eventType() == null || event.eventType().isBlank()
                || event.sourceService() == null || event.sourceService().isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "eventId, eventType and sourceService are required"));
        }
        publisher.publishExternal(event);
        return ResponseEntity.accepted().body(java.util.Map.of("eventId", event.eventId()));
    }
}
