package site.yuqi.ragindexer.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.ragindexer.jobs.IndexingJobUpdater;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/** Keeps Cloud Run request CPU available while the Kafka listener drains a job. */
@RestController
@RequestMapping("/api/internal/worker")
public class WorkerDrainController {

    private final IndexingJobUpdater jobs;
    private final byte[] expectedToken;

    public WorkerDrainController(IndexingJobUpdater jobs,
                                 @Value("${portfolio.internal-token:}") String token) {
        this.jobs = jobs;
        this.expectedToken = bytes(token);
    }

    @PostMapping("/drain/{jobId}")
    public ResponseEntity<Map<String, Object>> drain(
            @PathVariable UUID jobId,
            @RequestParam(defaultValue = "90000") long maxWaitMs,
            @RequestHeader(value = "X-Internal-Token", required = false) String token)
            throws InterruptedException {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        long deadline = System.currentTimeMillis() + Math.max(1000, Math.min(maxWaitMs, 240000));
        String status;
        do {
            status = jobs.status(jobId);
            if ("DONE".equals(status)) {
                return ResponseEntity.ok(Map.of("jobId", jobId, "status", status));
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", status));
    }

    private boolean authorized(String token) {
        byte[] actual = bytes(token);
        return expectedToken.length > 0 && MessageDigest.isEqual(expectedToken, actual);
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.trim().getBytes(StandardCharsets.UTF_8);
    }
}
