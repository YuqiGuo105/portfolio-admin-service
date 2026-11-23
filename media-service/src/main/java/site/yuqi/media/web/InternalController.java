package site.yuqi.media.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.intake.WorkEvent;
import site.yuqi.media.intake.WorkIntake;
import site.yuqi.media.model.Asset;
import site.yuqi.media.work.JobRunner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The only HTTP surface, for other services of the platform. Both calls need the shared internal
 * token; on Cloud Run the service is additionally private.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalController {

    private final MediaRepository repository;
    private final WorkIntake intake;
    private final JobRunner runner;
    private final byte[] expectedToken;
    private final boolean httpIntake;

    public InternalController(MediaRepository repository, WorkIntake intake, JobRunner runner,
                              MediaProperties properties,
                              @Value("${portfolio.media-pipeline.http-intake.enabled:false}") boolean httpIntake) {
        this.repository = repository;
        this.intake = intake;
        this.runner = runner;
        this.expectedToken = bytes(properties.internalToken());
        this.httpIntake = httpIntake;
    }

    /**
     * Wake-up call, same contract as the indexers. A scale-to-zero instance needs a request to get
     * CPU; this one holds the request open until the asset is finished or {@code maxWaitMs} passes.
     * It does not create work: that arrives through Kafka, or through the recovery sweep.
     */
    @PostMapping("/worker/drain/{assetId}")
    public ResponseEntity<Map<String, Object>> drain(
            @PathVariable UUID assetId,
            @RequestParam(defaultValue = "600000") long maxWaitMs,
            @RequestHeader(value = "X-Internal-Token", required = false) String token)
            throws InterruptedException {
        if (!authorized(token)) return unauthorized();
        runner.wake();
        long deadline = System.currentTimeMillis() + Math.max(1000, Math.min(maxWaitMs, 3_300_000));
        String status;
        do {
            status = repository.assetStatus(assetId);
            if (status == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(assetId, "UNKNOWN"));
            if (Asset.READY.equals(status) || Asset.FAILED.equals(status)) {
                return ResponseEntity.ok(body(assetId, status));
            }
            Thread.sleep(500);
        } while (System.currentTimeMillis() < deadline);
        return ResponseEntity.accepted().body(body(assetId, status));
    }

    /**
     * The same event the Kafka listener receives, delivered by HTTP. Off unless enabled; it exists
     * so the pipeline can be run on one machine without a broker.
     */
    @PostMapping("/media/events")
    public ResponseEntity<Map<String, Object>> event(
            @RequestBody WorkEvent event,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!authorized(token)) return unauthorized();
        if (!httpIntake) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "http intake is disabled"));
        try {
            return ResponseEntity.ok(Map.of("outcome", intake.accept(event).name()));
        } catch (WorkIntake.PoisonEventException poison) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", poison.getMessage()));
        }
    }

    private static Map<String, Object> body(UUID assetId, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assetId", assetId);
        body.put("status", status);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }

    private boolean authorized(String token) {
        return expectedToken.length > 0 && MessageDigest.isEqual(expectedToken, bytes(token));
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.trim().getBytes(StandardCharsets.UTF_8);
    }
}
