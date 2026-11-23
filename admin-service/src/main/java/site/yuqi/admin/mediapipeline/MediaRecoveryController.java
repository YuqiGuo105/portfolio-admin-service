package site.yuqi.admin.mediapipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** An external scheduler must invoke this endpoint to recover after scale-to-zero. */
@RestController
@ConditionalOnProperty(name="portfolio.media-pipeline.enabled", havingValue="true")
public class MediaRecoveryController {
    private final byte[] token;
    private final MediaOutboxRelay relay;
    private final MediaWakeDispatcher wakes;

    public MediaRecoveryController(MediaOutboxRelay relay, MediaWakeDispatcher wakes,
            @Value("${portfolio.media-pipeline.recovery-token:}") String token) {
        this.relay=relay; this.wakes=wakes; this.token=token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/internal/media/recover")
    public ResponseEntity<?> recover(@RequestHeader(value="X-Media-Recovery-Token",required=false) String supplied) {
        if (token.length<32 || supplied==null || !MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).header("Cache-Control","no-store").body(Map.of("error","unauthorized"));
        }
        int delivered=relay.drainOnce();
        // Keep the request alive so request-billed hosts retain CPU while workers drain.
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("delivered",delivered,"wake",wakes.drainAndAwait()));
    }
}
