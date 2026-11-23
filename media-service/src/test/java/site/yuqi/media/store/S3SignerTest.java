package site.yuqi.media.store;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the signer against the worked examples in the Amazon S3 API reference
 * ("Authenticating Requests (AWS Signature Version 4)"). Same keys, same date, same signatures.
 */
class S3SignerTest {

    private static final URI HOST = URI.create("https://examplebucket.s3.amazonaws.com");
    private static final Instant NOW = Instant.parse("2013-05-24T00:00:00Z");
    private final S3Signer signer = new S3Signer(
            "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");

    @Test
    void presignedGetMatchesTheDocumentedSignature() {
        URI url = signer.presign("GET", HOST, "/test.txt", Map.of(), Duration.ofSeconds(86400), NOW);

        assertThat(url.toString()).isEqualTo("https://examplebucket.s3.amazonaws.com/test.txt"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
    }

    @Test
    void getObjectWithRangeHeader() {
        Map<String, String> auth = signer.sign("GET", HOST, "/test.txt", Map.of(),
                Map.of("Range", "bytes=0-9"), S3Signer.EMPTY_SHA256, NOW);

        assertThat(auth.get("Authorization")).isEqualTo("AWS4-HMAC-SHA256 "
                + "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void putObjectWithASpecialCharacterInTheKey() {
        String bodyHash = S3Signer.sha256Hex("Welcome to Amazon S3.");
        Map<String, String> auth = signer.sign("PUT", HOST, "/test$file.text", Map.of(),
                Map.of("Date", "Fri, 24 May 2013 00:00:00 GMT", "x-amz-storage-class", "REDUCED_REDUNDANCY"),
                bodyHash, NOW);

        assertThat(bodyHash).isEqualTo("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");
        assertThat(auth.get("Authorization")).endsWith(
                "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, "
                        + "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd");
    }

    @Test
    void queryParametersAreSortedAndSigned() {
        Map<String, String> listing = signer.sign("GET", HOST, "/", Map.of("prefix", "J", "max-keys", "2"),
                Map.of(), S3Signer.EMPTY_SHA256, NOW);
        Map<String, String> lifecycle = signer.sign("GET", HOST, "/", Map.of("lifecycle", ""),
                Map.of(), S3Signer.EMPTY_SHA256, NOW);

        assertThat(listing.get("Authorization"))
                .endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7");
        assertThat(lifecycle.get("Authorization"))
                .endsWith("Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543");
    }

    @Test
    void pathStyleUrlsKeepThePortAndEncodeEachSegmentOnce() {
        URI local = URI.create("http://127.0.0.1:9000");
        URI url = signer.presign("PUT", local, "/originals/a b/视频.mp4",
                Map.of("partNumber", "2", "uploadId", "abc/def+1"), Duration.ofMinutes(30), NOW);

        assertThat(url.toString())
                .startsWith("http://127.0.0.1:9000/originals/a%20b/%E8%A7%86%E9%A2%91.mp4?")
                .contains("&partNumber=2&uploadId=abc%2Fdef%2B1")
                .contains("X-Amz-Expires=1800");
        assertThat(S3Signer.hostHeader(local)).isEqualTo("127.0.0.1:9000");
        assertThat(S3Signer.hostHeader(URI.create("https://storage.googleapis.com:443"))).isEqualTo("storage.googleapis.com");
    }
}
