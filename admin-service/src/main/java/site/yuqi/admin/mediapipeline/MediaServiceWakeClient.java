package site.yuqi.admin.mediapipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import site.yuqi.admin.events.CloudRunIdentityTokenProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Same wake-up contract as the indexers: {@code POST /api/internal/worker/drain/{id}}. On Cloud
 * Run an idle service has no CPU until a request arrives; this request gives the Media Service CPU
 * and holds it until the asset is finished or the wait runs out. It is a nudge, not a delivery:
 * the work itself always travels through Kafka.
 */
@Component
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaServiceWakeClient {
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(MediaServiceWakeClient.class);

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final MediaPipelineProperties.MediaService target;
    private final CloudRunIdentityTokenProvider identityTokens;

    public MediaServiceWakeClient(MediaPipelineProperties properties, CloudRunIdentityTokenProvider identityTokens) {
        this.target = properties.mediaService();
        this.identityTokens = identityTokens;
    }

    public boolean wakeAndAwait(UUID assetId) {
        String base = target.baseUrl() == null ? "" : target.baseUrl().trim().replaceAll("/+$", "");
        if (base.isBlank() || target.internalToken().isBlank()) {
            log.debug("Media Service wake is not configured; relying on its Kafka consumer");
            return false;
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                            base + "/api/internal/worker/drain/" + assetId + "?maxWaitMs=" + waitMillis()))
                    .timeout(Duration.ofMillis(waitMillis() + 15_000L))
                    .header("X-Internal-Token", target.internalToken())
                    .POST(HttpRequest.BodyPublishers.noBody());
            if (target.identityToken()) request.header("Authorization", "Bearer " + identityTokens.tokenFor(base));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            var body = response.statusCode()==200 ? mapper.readTree(response.body()) : null;
            boolean finished = body!=null && assetId.toString().equals(body.path("assetId").asText())
                    && java.util.Set.of("READY","FAILED").contains(body.path("status").asText());
            if (!finished) log.info("Media Service wake for asset {} returned HTTP {}", assetId, response.statusCode());
            return finished;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            log.warn("Media Service wake for asset {} failed: {}", assetId, error.getMessage());
            return false;
        }
    }

    public boolean isConfigured() {
        return target.baseUrl()!=null && !target.baseUrl().isBlank()
                && target.internalToken()!=null && !target.internalToken().isBlank();
    }

    public long waitMillis() { return Math.max(1000,Math.min(600_000,target.wakeWaitMs())); }
}
