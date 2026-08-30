package site.yuqi.admin.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SupabaseContentCoverStorage implements ContentCoverStorage {

    private final String supabaseUrl;
    private final String serviceRoleKey;
    private final String bucket;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SupabaseContentCoverStorage(
            @Value("${portfolio.supabase.url:}") String supabaseUrl,
            @Value("${portfolio.supabase.service-role-key:}") String serviceRoleKey,
            @Value("${portfolio.media.cover-bucket:project-covers}") String bucket,
            ObjectMapper objectMapper) {
        this.supabaseUrl = stripTrailingSlash(supabaseUrl);
        this.serviceRoleKey = serviceRoleKey;
        this.bucket = requireSafeBucket(bucket);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public StoredCover put(String objectPath, byte[] bytes, String mimeType) {
        requireConfigured();
        String objectUrl = supabaseUrl + "/storage/v1/object/" + encodePath(bucket + "/" + objectPath);
        HttpRequest request = HttpRequest.newBuilder(URI.create(objectUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey)
                .header("Content-Type", mimeType)
                .header("cache-control", "public, max-age=31536000, immutable")
                .header("x-upsert", "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean created = response.statusCode() >= 200 && response.statusCode() < 300;
            if (!created && response.statusCode() != 409) {
                throw new IllegalStateException("Supabase Storage upload failed with HTTP " + response.statusCode() + ".");
            }
            String publicUrl = supabaseUrl + "/storage/v1/object/public/" + encodePath(bucket + "/" + objectPath);
            return new StoredCover(objectPath, publicUrl, created);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Supabase Storage upload was interrupted.", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Supabase Storage upload failed.", ex);
        }
    }

    @Override
    public void delete(String objectPath) {
        requireConfigured();
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of("prefixes", List.of(objectPath)));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(supabaseUrl + "/storage/v1/object/" + encodeSegment(bucket)))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .header("apikey", serviceRoleKey)
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Compensating cover delete failed for {} with HTTP {}", objectPath, response.statusCode());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Compensating cover delete interrupted for {}", objectPath);
        } catch (Exception ex) {
            log.warn("Compensating cover delete failed for {}", objectPath, ex);
        }
    }

    @Override
    public String bucket() {
        return bucket;
    }

    private void requireConfigured() {
        if (supabaseUrl.isBlank() || serviceRoleKey == null || serviceRoleKey.isBlank()) {
            throw new IllegalStateException("Supabase cover storage is not configured.");
        }
    }

    private static String encodePath(String path) {
        return String.join("/", java.util.Arrays.stream(path.split("/"))
                .map(SupabaseContentCoverStorage::encodeSegment)
                .toList());
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String requireSafeBucket(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("Invalid Supabase cover bucket name.");
        }
        return value;
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
