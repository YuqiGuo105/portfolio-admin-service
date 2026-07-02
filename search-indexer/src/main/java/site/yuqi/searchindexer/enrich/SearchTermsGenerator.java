package site.yuqi.searchindexer.enrich;

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
 * Document-expansion (doc2query-style) enrichment backed by Google Gemini.
 * Called <b>once per document</b> at publish / reindex time to produce a bag of
 * search-oriented terms — keywords, synonyms, related technologies/concepts, and
 * common question phrasings — that are indexed into the analyzed
 * {@code search_terms} text field.
 *
 * <p>This deliberately front-loads the "semantic" work into the offline indexing
 * phase so that query-time retrieval stays pure BM25 lexical matching with
 * <b>zero per-query API cost</b> and no query embedding / local model. It is the
 * classic document-expansion trade-off: spend one cheap chat call per document
 * up-front to widen lexical recall, versus embedding every query at read time.
 * Gemini Flash-Lite is used because it is the cheapest capable model for this
 * throwaway generation task.
 *
 * <p><b>Fail-open contract:</b> any missing API key, timeout, HTTP error, or
 * malformed response returns an empty string and logs a warning. The document is
 * still indexed (without {@code search_terms}); search continues to work on the
 * original fields. Enrichment must never break the indexing pipeline.
 *
 * <p>Uses the Gemini {@code v1beta generateContent} endpoint (same shape as the
 * agent-service {@code GeminiIntentClassifier}) with {@code thinkingBudget=0} to
 * suppress hidden "thinking" tokens on Gemini-2.5 models.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchTermsGenerator {

    private final RestTemplate geminiRestTemplate;

    @Value("${portfolio.gemini.api-key:}")
    private String apiKey;

    @Value("${portfolio.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String baseUrl;

    @Value("${portfolio.gemini.model:gemini-2.5-flash-lite}")
    private String model;

    @Value("${portfolio.gemini.thinking-budget:0}")
    private int thinkingBudget;

    @Value("${portfolio.search-terms.enabled:true}")
    private boolean enabled;

    @Value("${portfolio.search-terms.max-input-chars:4000}")
    private int maxInputChars;

    private static final String SYSTEM_PROMPT = """
            You expand documents for a keyword (BM25) search engine.
            Given a document, output a single line of comma-separated search terms:
            important keywords, synonyms, well-known abbreviations and acronyms,
            related technologies and concepts, and the way a user would phrase
            questions to find this document.
            Rules: output ONLY the comma-separated terms, no numbering, no explanation,
            no surrounding quotes. Prefer lowercase. 15-40 terms.""";

    /**
     * Generates the {@code search_terms} string for a document. Returns an empty
     * string when disabled, unconfigured, or on any failure (fail-open).
     *
     * @param title   document title (may be null)
     * @param summary document summary/description (may be null)
     * @param body    document body/content (may be null; truncated for token cost)
     * @return comma-separated expansion terms, or {@code ""} on any failure
     */
    public String generate(String title, String summary, String body) {
        if (!enabled) {
            return "";
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("SEARCH_TERMS skipped: GEMINI_API_KEY not configured");
            return "";
        }

        String input = buildInput(title, summary, body);
        if (input.isBlank()) {
            return "";
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", apiKey);

            Map<String, Object> payload = Map.of(
                    "systemInstruction", Map.of(
                            "parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                    "contents", List.of(Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("text", input)))),
                    "generationConfig", Map.of(
                            "temperature", 0,
                            "thinkingConfig", Map.of("thinkingBudget", thinkingBudget)));

            HttpEntity<Map<String, Object>> req = new HttpEntity<>(payload, headers);
            String url = baseUrl.replaceAll("/$", "") + "/models/" + model + ":generateContent";

            GeminiResponse resp = geminiRestTemplate.postForObject(url, req, GeminiResponse.class);
            String terms = extractText(resp);
            if (terms == null || terms.isBlank()) {
                log.warn("SEARCH_TERMS empty response from Gemini (model={})", model);
                return "";
            }
            return normalize(terms);
        } catch (Exception e) {
            log.warn("SEARCH_TERMS generation failed (fail-open, document indexed without terms): {}",
                    e.getMessage());
            return "";
        }
    }

    private String buildInput(String title, String summary, String body) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank())   sb.append("Title: ").append(title).append('\n');
        if (summary != null && !summary.isBlank()) sb.append("Summary: ").append(summary).append('\n');
        if (body != null && !body.isBlank())      sb.append("Body: ").append(body);
        String input = sb.toString().strip();
        if (input.length() > maxInputChars) {
            input = input.substring(0, maxInputChars);
        }
        return input;
    }

    private static String extractText(GeminiResponse resp) {
        if (resp == null || resp.getCandidates() == null || resp.getCandidates().isEmpty()) {
            return null;
        }
        Candidate first = resp.getCandidates().get(0);
        if (first.getContent() == null
                || first.getContent().getParts() == null
                || first.getContent().getParts().isEmpty()) {
            return null;
        }
        return first.getContent().getParts().get(0).getText();
    }

    /** Collapse whitespace/newlines the model may emit into a clean single line. */
    private static String normalize(String terms) {
        return terms.replaceAll("[\\r\\n]+", ", ")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("(,\\s*){2,}", ", ")
                .strip();
    }

    // --- DTOs (Gemini v1beta generateContent response) ---------------------

    @Data
    public static class GeminiResponse {
        @JsonProperty("candidates")
        private List<Candidate> candidates;
    }

    @Data
    public static class Candidate {
        @JsonProperty("content")
        private Content content;
        @JsonProperty("finishReason")
        private String finishReason;
    }

    @Data
    public static class Content {
        @JsonProperty("parts")
        private List<Part> parts;
    }

    @Data
    public static class Part {
        @JsonProperty("text")
        private String text;
    }
}
