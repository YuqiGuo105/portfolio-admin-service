package site.yuqi.admin.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Supplies audience-scoped Google ID tokens for private Cloud Run workers. */
@Component
public class CloudRunIdentityTokenProvider {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper objectMapper;
    private final String metadataIdentityUrl;
    private final Map<String, CachedToken> cache = new HashMap<>();

    public CloudRunIdentityTokenProvider(
            ObjectMapper objectMapper,
            @Value("${portfolio.gcp.metadata-identity-url:http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity}")
            String metadataIdentityUrl) {
        this.objectMapper = objectMapper;
        this.metadataIdentityUrl = metadataIdentityUrl;
    }

    public synchronized String tokenFor(String audience) throws Exception {
        Instant now = Instant.now();
        CachedToken cached = cache.get(audience);
        if (cached != null && now.isBefore(cached.refreshAt())) {
            return cached.value();
        }

        String query = "audience=" + URLEncoder.encode(audience, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(metadataIdentityUrl + "?" + query))
                .timeout(Duration.ofSeconds(5))
                .header("Metadata-Flavor", "Google")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
            throw new IllegalStateException("metadata identity endpoint returned " + response.statusCode());
        }

        String token = response.body().trim();
        Instant refreshAt = tokenExpiry(token).minusSeconds(60);
        if (!refreshAt.isAfter(now)) {
            refreshAt = now.plusSeconds(60);
        }
        cache.put(audience, new CachedToken(token, refreshAt));
        return token;
    }

    private Instant tokenExpiry(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return Instant.now().plusSeconds(300);
        }
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        JsonNode claims = objectMapper.readTree(payload);
        long exp = claims.path("exp").asLong(Instant.now().plusSeconds(300).getEpochSecond());
        return Instant.ofEpochSecond(exp);
    }

    private record CachedToken(String value, Instant refreshAt) {}
}
