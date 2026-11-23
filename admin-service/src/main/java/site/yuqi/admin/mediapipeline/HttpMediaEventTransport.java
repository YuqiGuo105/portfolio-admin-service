package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Delivers the same event straight to the Media Service's internal endpoint. For single-machine runs. */
@Component
@ConditionalOnExpression("'${portfolio.media-pipeline.enabled:false}' == 'true' "
        + "and '${portfolio.media-pipeline.events.transport:kafka}' == 'http'")
public class HttpMediaEventTransport implements MediaEventTransport {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final MediaPipelineProperties.MediaService target;

    public HttpMediaEventTransport(ObjectMapper mapper, MediaPipelineProperties properties) {
        this.mapper = mapper;
        this.target = properties.mediaService();
    }

    @Override
    public void send(MediaWorkEvent event) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(target.baseUrl().replaceAll("/+$", "") + "/api/internal/media/events"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", target.internalToken())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(event)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Media Service answered HTTP " + response.statusCode() + ": " + response.body());
        }
    }
}
