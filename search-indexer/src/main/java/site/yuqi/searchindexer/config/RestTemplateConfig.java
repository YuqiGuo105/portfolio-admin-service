package site.yuqi.searchindexer.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Dedicated {@link RestTemplate} for the one-time-per-document Gemini call that
 * generates {@code search_terms}. Short read timeout — search-terms generation
 * is best-effort and must never stall the indexing pipeline.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate geminiRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }
}
