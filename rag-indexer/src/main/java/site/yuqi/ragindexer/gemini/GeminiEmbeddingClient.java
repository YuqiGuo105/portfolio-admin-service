package site.yuqi.ragindexer.gemini;

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
 * Calls the Google Gemini {@code embedContent} endpoint to produce a vector
 * for a piece of text.
 *
 * <p>Uses {@code gemini-embedding-001} with {@code output_dimensionality=1536}
 * so the vectors line up with the {@code embedding vector(1536)} column on
 * {@code kb_documents} (the same column shape the previous OpenAI
 * {@code text-embedding-3-small} client wrote to).
 *
 * <p>Endpoint:
 * {@code POST {base-url}/v1beta/models/{model}:embedContent?key={api-key}}
 *
 * <p>Request body:
 * <pre>
 * {
 *   "content": {"parts": [{"text": "..."}]},
 *   "output_dimensionality": 1536,
 *   "task_type": "RETRIEVAL_DOCUMENT"
 * }
 * </pre>
 *
 * <p>Response body:
 * <pre>
 * { "embedding": {"values": [1536 floats...]} }
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiEmbeddingClient {

    private final RestTemplate geminiRestTemplate;

    @Value("${portfolio.gemini.api-key:}")
    private String apiKey;

    @Value("${portfolio.gemini.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    @Value("${portfolio.gemini.embedding-model:gemini-embedding-001}")
    private String model;

    @Value("${portfolio.gemini.embedding-dimensions:1536}")
    private int outputDimensions;

    @Value("${portfolio.gemini.task-type:RETRIEVAL_DOCUMENT}")
    private String taskType;

    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY not configured");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "output_dimensionality", outputDimensions,
                "task_type", taskType
        );
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        String url = baseUrl.replaceAll("/$", "")
                + "/v1beta/models/" + model + ":embedContent?key=" + apiKey;

        EmbeddingResponse resp = geminiRestTemplate.postForObject(url, req, EmbeddingResponse.class);

        if (resp == null || resp.getEmbedding() == null || resp.getEmbedding().getValues() == null
                || resp.getEmbedding().getValues().isEmpty()) {
            throw new IllegalStateException("Empty embedding response from Gemini");
        }
        List<Float> values = resp.getEmbedding().getValues();
        if (values.size() != outputDimensions) {
            log.warn("Gemini returned embedding of size {} but expected {} — check model/dimensions",
                    values.size(), outputDimensions);
        }
        float[] vec = new float[values.size()];
        for (int i = 0; i < values.size(); i++) vec[i] = values.get(i);
        return vec;
    }

    // --- DTOs --------------------------------------------------------------

    @Data
    public static class EmbeddingResponse {
        @JsonProperty("embedding")
        private Embedding embedding;
    }

    @Data
    public static class Embedding {
        @JsonProperty("values")
        private List<Float> values;
    }
}
