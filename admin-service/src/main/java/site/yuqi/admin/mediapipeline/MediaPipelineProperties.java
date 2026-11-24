package site.yuqi.admin.mediapipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the upload side of the media pipeline. Everything is inert until
 * {@code portfolio.media-pipeline.enabled} is true. (The older {@code portfolio.media.*} keys
 * belong to content covers and are unrelated.)
 */
@ConfigurationProperties(prefix = "portfolio.media-pipeline")
public record MediaPipelineProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("v1") String profileVersion,
        @DefaultValue Storage storage,
        @DefaultValue Upload upload,
        @DefaultValue Events events,
        @DefaultValue MediaService mediaService) {

    /** The private bucket originals are uploaded into. Any S3-compatible endpoint. */
    public record Storage(
            @DefaultValue("http://127.0.0.1:9000") String endpoint,
            @DefaultValue("auto") String region,
            @DefaultValue("") String accessKey,
            @DefaultValue("") String secretKey,
            @DefaultValue("portfolio-media-originals") String originalsBucket,
            @DefaultValue("portfolio-media-public") String publicBucket) {
    }

    public record Upload(
            @DefaultValue("8388608") long partSizeBytes,
            @DefaultValue("2147483648") long maxVideoBytes,
            @DefaultValue("52428800") long maxImageBytes,
            @DefaultValue("3600") int urlTtlSeconds,
            @DefaultValue("86400") int sessionTtlSeconds) {
    }

    /** How "upload complete" reaches the Media Service: the existing Kafka topic, or plain HTTP on one machine. */
    public record Events(
            @DefaultValue("kafka") String transport,
            @DefaultValue("content.search.index.v1") String topic,
            @DefaultValue("5000") long relayIntervalMs,
            @DefaultValue("20") int maxAttempts) {
    }

    public record MediaService(
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String internalToken,
            @DefaultValue("true") boolean identityToken,
            @DefaultValue("600000") long wakeWaitMs) {
    }
}
