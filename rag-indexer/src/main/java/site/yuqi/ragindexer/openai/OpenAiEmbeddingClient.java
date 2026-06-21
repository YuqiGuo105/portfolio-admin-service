package site.yuqi.ragindexer.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Calls the OpenAI embeddings endpoint to produce a vector for a piece of text.
 *
 * <p>Uses {@code text-embedding-3-small} by default (1536 dims) which matches
 * the {@code embedding vector(1536)} column on {@code kb_documents}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiEmbeddingClient {

    private final RestTemplate openAiRestTemplate;

    @Value("${portfolio.openai.api-key}")
    private String apiKey;

    @Value("${portfolio.openai.base-url}")
    private String baseUrl;

    @Value("${portfolio.openai.embedding-model}")
    private String model;

    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY not configured");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of("model", model, "input", text);
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        String url = baseUrl.replaceAll("/$", "") + "/v1/embeddings";
        EmbeddingResponse resp = openAiRestTemplate.postForObject(url, req, EmbeddingResponse.class);

        if (resp == null || resp.getData() == null || resp.getData().isEmpty()) {
            throw new IllegalStateException("Empty embedding response from OpenAI");
        }
        List<Float> values = resp.getData().get(0).getEmbedding();
        float[] vec = new float[values.size()];
        for (int i = 0; i < values.size(); i++) vec[i] = values.get(i);
        return vec;
    }

    // --- DTOs --------------------------------------------------------------

    @Data
    public static class EmbeddingResponse {
        private List<Datum> data;
    }

    @Data
    public static class Datum {
        @JsonProperty("embedding")
        private List<Float> embedding;
        @JsonProperty("index")
        private int index;
    }
}
