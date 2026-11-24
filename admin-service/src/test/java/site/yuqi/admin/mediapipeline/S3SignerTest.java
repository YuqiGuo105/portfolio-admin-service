package site.yuqi.admin.mediapipeline;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class S3SignerTest {
    @Test void googlePreconditionUsesNativeSigningAndBindsTheGeneration() {
        var signer = new S3Signer("test-access", "test-secret", "auto");
        var endpoint = URI.create("https://storage.googleapis.com");
        var now = Instant.parse("2026-11-24T00:00:00Z");
        var headers = signer.sign("DELETE", endpoint, "/bucket/original.mp4", Map.of(),
                Map.of("X-Goog-If-Generation-Match", "123"), S3Signer.EMPTY_SHA256, now);
        assertThat(headers).containsOnlyKeys("x-goog-date", "x-goog-content-sha256", "Authorization");
        assertThat(headers.get("Authorization")).isEqualTo("GOOG4-HMAC-SHA256 "
                + "Credential=test-access/20261124/auto/storage/goog4_request, "
                + "SignedHeaders=host;x-goog-content-sha256;x-goog-date;x-goog-if-generation-match, "
                + "Signature=fb9a0a405384ca84034ce5f33e1ded343702764a72526a46ea97d820fb0fb9fc");
        assertThat(signer.sign("DELETE", endpoint, "/bucket/original.mp4", Map.of(),
                Map.of("x-goog-if-generation-match", "124"), S3Signer.EMPTY_SHA256, now))
                .isNotEqualTo(headers);
    }

    @Test void mixedHeaderFamiliesAreRejectedBeforeSending() {
        var signer = new S3Signer("test", "test", "auto");
        assertThatThrownBy(() -> signer.sign("DELETE", URI.create("https://storage.googleapis.com"),
                "/bucket/original.mp4", Map.of(), Map.of("x-goog-if-generation-match", "1", "X-Amz-Date", "date"),
                S3Signer.EMPTY_SHA256, Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void existingAwsSignaturesAndPresignedUploadsRemainUnchanged() {
        var signer = new S3Signer("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
        var endpoint = URI.create("https://examplebucket.s3.amazonaws.com");
        var now = Instant.parse("2013-05-24T00:00:00Z");
        var headers = signer.sign("GET", endpoint, "/test.txt", Map.of(), Map.of("Range", "bytes=0-9"), S3Signer.EMPTY_SHA256, now);
        assertThat(headers).containsOnlyKeys("x-amz-date", "x-amz-content-sha256", "Authorization");
        assertThat(headers.get("Authorization")).isEqualTo("AWS4-HMAC-SHA256 "
                + "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
        assertThat(signer.presign("GET", endpoint, "/test.txt", Map.of(), Duration.ofSeconds(86400), now).toString())
                .endsWith("X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
    }
}
