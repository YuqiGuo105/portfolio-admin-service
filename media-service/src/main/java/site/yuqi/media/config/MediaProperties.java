package site.yuqi.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Everything the Media Service can be told from outside. See application.yml for the env names. */
@ConfigurationProperties(prefix = "portfolio.media-pipeline")
public record MediaProperties(
        @DefaultValue("v1") String profileVersion,
        @DefaultValue("") String internalToken,
        @DefaultValue Storage storage,
        @DefaultValue Worker worker,
        @DefaultValue Tools tools,
        @DefaultValue Limits limits,
        @DefaultValue Kafka kafka,
        @DefaultValue Recovery recovery) {

    /** One S3-compatible endpoint, two buckets: originals stay private, outputs are public. */
    public record Storage(
            @DefaultValue("http://127.0.0.1:9000") String endpoint,
            @DefaultValue("auto") String region,
            @DefaultValue("") String accessKey,
            @DefaultValue("") String secretKey,
            @DefaultValue("portfolio-media-originals") String originalsBucket,
            @DefaultValue("portfolio-media-public") String publicBucket) {
    }

    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2") int concurrency,
            @DefaultValue("120") int leaseSeconds,
            @DefaultValue("2000") long pollIntervalMs,
            @DefaultValue("") String workDir,
            @DefaultValue("2147483648") long sourceCacheBytes) {
    }

    public record Tools(
            @DefaultValue("ffmpeg") String ffmpeg,
            @DefaultValue("ffprobe") String ffprobe,
            @DefaultValue("vips") String vips,
            @DefaultValue("vipsheader") String vipsheader,
            @DefaultValue("veryfast") String x264Preset,
            @DefaultValue("3600") int jobTimeoutSeconds) {
    }

    public record Limits(
            @DefaultValue("1800") int maxVideoSeconds,
            @DefaultValue("4320") int maxVideoShortSide,
            @DefaultValue("120000000") long maxImagePixels,
            @DefaultValue("15") int loopMaxSeconds) {
    }

    public record Kafka(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("content.search.index.v1") String topic) {
    }

    public record Recovery(
            @DefaultValue("60") int unplannedAfterSeconds) {
    }
}
