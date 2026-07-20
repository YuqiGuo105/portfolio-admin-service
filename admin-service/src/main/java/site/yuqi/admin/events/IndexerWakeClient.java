package site.yuqi.admin.events;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Request-scoped wake signal for scale-to-zero Kafka indexers. */
@Component
@Slf4j
public class IndexerWakeClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String searchBaseUrl;
    private final String ragBaseUrl;
    private final String token;
    private final CloudRunIdentityTokenProvider identityTokens;

    public IndexerWakeClient(
            @Value("${portfolio.indexers.search-url:}") String searchBaseUrl,
            @Value("${portfolio.indexers.rag-url:}") String ragBaseUrl,
            @Value("${portfolio.indexers.internal-token:}") String token,
            CloudRunIdentityTokenProvider identityTokens) {
        this.searchBaseUrl = stripSlash(searchBaseUrl);
        this.ragBaseUrl = stripSlash(ragBaseUrl);
        this.token = token == null ? "" : token.trim();
        this.identityTokens = identityTokens;
    }

    public boolean wakeAndAwait(IndexingJob job) {
        String base = job.getJobType() == JobType.SEARCH_INDEX ? searchBaseUrl : ragBaseUrl;
        long waitMs = job.getJobType() == JobType.SEARCH_INDEX ? 45_000L : 180_000L;
        if (base.isBlank() || token.isBlank()) {
            log.warn("Indexer wake is not configured for {}", job.getJobType());
            return false;
        }
        try {
            String identityToken = identityTokens.tokenFor(base);
            URI uri = URI.create(base + "/api/internal/worker/drain/" + job.getId()
                    + "?maxWaitMs=" + waitMs);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(waitMs + 15_000L))
                    .header("Authorization", "Bearer " + identityToken)
                    .header("X-Internal-Token", token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            boolean done = response.statusCode() == 200;
            if (!done) {
                log.warn("Indexer wake incomplete job={} type={} status={}",
                        job.getId(), job.getJobType(), response.statusCode());
            }
            return done;
        } catch (Exception error) {
            log.warn("Indexer wake failed job={} type={} error={}",
                    job.getId(), job.getJobType(), error.getMessage());
            return false;
        }
    }

    private static String stripSlash(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }
}
