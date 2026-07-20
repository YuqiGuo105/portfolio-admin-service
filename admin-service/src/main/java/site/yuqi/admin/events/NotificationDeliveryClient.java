package site.yuqi.admin.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Idempotent HTTP fast path that also wakes a scale-to-zero notification worker. */
@Component
@Slf4j
public class NotificationDeliveryClient {

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl;
    private final String token;

    public NotificationDeliveryClient(
            ObjectMapper mapper,
            @Value("${portfolio.notification.base-url:}") String baseUrl,
            @Value("${portfolio.notification.internal-token:}") String token) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
    }

    public boolean deliver(ContentPublishedEvent event) {
        if (baseUrl.isBlank() || token.isBlank()) return false;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("eventType", eventType(event.getSourceType()));
            body.put("topic", event.getNotificationTopic());
            body.put("title", event.getTitle());
            body.put("summary", event.getSummary());
            body.put("url", event.getUrl());
            body.put("sourceType", event.getSourceType());
            body.put("sourceId", event.getSourceId());
            body.put("idempotencyKey", event.getIdempotencyKey());
            body.put("metadata", Map.of("sourceVersion", event.getSourceVersion()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/content-events"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception error) {
            log.warn("Notification wake failed idempotencyKey={} error={}",
                    event.getIdempotencyKey(), error.getMessage());
            return false;
        }
    }

    private static String eventType(String sourceType) {
        return switch (sourceType) {
            case "BLOG", "LIFE_BLOG" -> "ARTICLE_PUBLISHED";
            case "PROJECT" -> "FEATURE_RELEASED";
            case "EXPERIENCE" -> "JOB_POSITION_UPDATED";
            default -> throw new IllegalArgumentException("Unsupported source type: " + sourceType);
        };
    }
}
