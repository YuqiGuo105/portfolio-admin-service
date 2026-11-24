package site.yuqi.admin.mediapipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The originals bucket, as far as the upload API needs it: open a multipart upload, hand out one
 * signed URL per part, and close the upload once every part is there.
 *
 * <p>The browser sends the bytes straight to the bucket with those URLs. This service never sees
 * them, so a 1 GB video costs it a few small requests.
 */
@Component
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class OriginalsStore {

    public record Part(int number, String etag, long size) {
    }

    public record StoredObject(long size, String etag, String generation) {
    }

    /** Safe diagnostics only: never retain provider response bodies, object paths or credentials. */
    public static final class DeletionRequestException extends IOException {
        private final int status;
        private final String providerCode;

        DeletionRequestException(HttpResponse<String> response) {
            super("Original deletion failed: HTTP " + response.statusCode());
            status = response.statusCode();
            Matcher code = Pattern.compile("<Code>([A-Za-z0-9_.-]{1,80})</Code>")
                    .matcher(response.body() == null ? "" : response.body());
            providerCode = code.find() ? code.group(1) : "Unknown";
        }

        public int status() { return status; }
        public String providerCode() { return providerCode; }
    }

    private static final Pattern UPLOAD_ID = Pattern.compile("<UploadId>([^<]+)</UploadId>");
    private static final Pattern PART = Pattern.compile(
            "<Part>.*?<PartNumber>(\\d+)</PartNumber>.*?</Part>", Pattern.DOTALL);
    private static final Pattern ETAG = Pattern.compile("<ETag>([^<]+)</ETag>");
    private static final Pattern SIZE = Pattern.compile("<Size>(\\d+)</Size>");
    private static final Pattern TRUNCATED = Pattern.compile("<IsTruncated>true</IsTruncated>");
    private static final Pattern NEXT_MARKER = Pattern.compile("<NextPartNumberMarker>(\\d+)</NextPartNumberMarker>");

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final URI endpoint;
    private final String bucket;
    private final String publicBucket;
    private final S3Signer signer;

    public OriginalsStore(MediaPipelineProperties properties) {
        MediaPipelineProperties.Storage storage = properties.storage();
        this.endpoint = URI.create(storage.endpoint().replaceAll("/+$", ""));
        this.bucket = storage.originalsBucket();
        this.publicBucket = storage.publicBucket();
        this.signer = new S3Signer(storage.accessKey(), storage.secretKey(), storage.region());
    }

    /** Opens a multipart upload and returns the store's id for it. */
    public String createMultipartUpload(String key, String contentType) throws IOException {
        HttpResponse<String> response = send("POST", key, Map.of("uploads", ""),
                Map.of("Content-Type", contentType), "");
        Matcher id = UPLOAD_ID.matcher(requireSuccess(response, "create upload", key));
        if (!id.find()) throw new IOException("the object store returned no upload id for " + key);
        return unescape(id.group(1));
    }

    /** A URL the browser can PUT exactly one part to, valid for {@code ttl}. */
    public URI presignPart(String key, String storageUploadId, int partNumber, Duration ttl) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("partNumber", Integer.toString(partNumber));
        query.put("uploadId", storageUploadId);
        return signer.presign("PUT", endpoint, path(key), query, ttl, Instant.now());
    }

    /**
     * The parts the store actually holds. This list, not the browser's word, decides completion.
     * Empty when nothing was uploaded, and also when the store no longer knows the upload id.
     */
    public List<Part> listParts(String key, String storageUploadId) throws IOException {
        List<Part> parts = new ArrayList<>();
        String marker = null;
        do {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("uploadId", storageUploadId);
            if (marker != null) query.put("part-number-marker", marker);
            HttpResponse<String> response = send("GET", key, query, Map.of(), "");
            // The store forgets an upload id once the upload is completed or aborted.
            if (response.statusCode() == 404) return List.of();
            String body = requireSuccess(response, "list parts", key);
            Matcher part = PART.matcher(body);
            while (part.find()) {
                Matcher etag = ETAG.matcher(part.group());
                Matcher size = SIZE.matcher(part.group());
                parts.add(new Part(Integer.parseInt(part.group(1)),
                        etag.find() ? unescape(etag.group(1)) : "", size.find() ? Long.parseLong(size.group(1)) : 0));
            }
            Matcher next = NEXT_MARKER.matcher(body);
            marker = TRUNCATED.matcher(body).find() && next.find() ? next.group(1) : null;
        } while (marker != null);
        return parts;
    }

    /** Joins the parts into the final object. False when the upload id is no longer known to the store. */
    public boolean completeMultipartUpload(String key, String storageUploadId, List<Part> parts) throws IOException {
        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        for (Part part : parts) {
            xml.append("<Part><PartNumber>").append(part.number()).append("</PartNumber><ETag>")
                    .append(escape(part.etag())).append("</ETag></Part>");
        }
        xml.append("</CompleteMultipartUpload>");
        HttpResponse<String> response = send("POST", key, Map.of("uploadId", storageUploadId),
                Map.of("Content-Type", "application/xml"), xml.toString());
        if (response.statusCode() == 404 || response.body().contains("<Code>NoSuchUpload</Code>")) return false;
        String body = requireSuccess(response, "complete upload", key);
        // S3 can answer 200 and still report a failure in the body.
        if (body.contains("<Error>")) throw new IOException("complete upload failed for " + key + ": " + brief(body));
        return true;
    }

    public void abortMultipartUpload(String key, String storageUploadId) throws IOException {
        HttpResponse<String> response = send("DELETE", key, Map.of("uploadId", storageUploadId), Map.of(), "");
        if (response.statusCode() != 404) requireSuccess(response, "abort upload", key);
    }

    public Optional<StoredObject> head(String key) throws IOException {
        HttpResponse<String> response = send("HEAD", key, Map.of(), Map.of(), "");
        if (response.statusCode() == 404) return Optional.empty();
        requireSuccess(response, "head", key);
        return Optional.of(new StoredObject(
                response.headers().firstValueAsLong("Content-Length").orElse(-1),
                response.headers().firstValue("ETag").orElse("").replace("\"", ""),
                response.headers().firstValue("x-goog-generation").orElse(null)));
    }

    public String storageEndpoint() { return S3Signer.origin(endpoint); }
    public String storageBucket() { return bucket; }

    public String deletionBucket() {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.equals(publicBucket)) {
            throw new IllegalStateException("A separate private originals bucket is required");
        }
        return bucket;
    }

    /** No arbitrary paths, prefixes, public buckets or bulk deletion are accepted. */
    public void validateDeletion(UUID assetId, String expectedBucket, String key) {
        if (!deletionBucket().equals(expectedBucket) || key == null
                || !key.matches("originals/" + assetId + "/[a-f0-9]{20}/[A-Za-z0-9._-]{1,120}")
                || key.endsWith("/.") || key.endsWith("/..")) {
            throw new IllegalStateException("Original storage ownership could not be verified");
        }
    }

    /** Pin the observed GCS generation in the durable intent; never adopt a replacement on retry. */
    public String deletionGeneration(UUID assetId, String expectedBucket, String key) throws IOException {
        validateDeletion(assetId, expectedBucket, key);
        HttpResponse<String> before = send("HEAD", key, Map.of(), Map.of(), "");
        if (before.statusCode() == 404) return "0";
        requireSuccess(before, "head original", key);
        return generation(before);
    }

    /** Legacy ownership needs an existing object matching the original upload receipt, never just a key. */
    public String verifyLegacyOriginal(UUID assetId, String key, long expectedBytes, String expectedEtag) throws IOException {
        validateDeletion(assetId, deletionBucket(), key);
        if (expectedBytes <= 0 || expectedEtag == null || expectedEtag.isBlank())
            throw new IllegalStateException("The original upload receipt is incomplete");
        HttpResponse<String> response = send("HEAD", key, Map.of(), Map.of(), "");
        requireSuccess(response, "verify original receipt", key);
        String etag = response.headers().firstValue("ETag").orElse("").replace("\"", "");
        if (!expectedEtag.equals(etag) || response.headers().firstValueAsLong("Content-Length").orElse(-1) != expectedBytes)
            throw new IllegalStateException("The stored original does not match its upload receipt");
        return generation(response);
    }

    private static String generation(HttpResponse<String> before) throws IOException {
        if (before.headers().firstValue("x-amz-version-id").isPresent()) {
            throw new IOException("Versioned originals require a separate permanent-deletion workflow");
        }
        String generation = before.headers().firstValue("x-goog-generation").orElse("");
        if (!generation.matches("[1-9][0-9]*"))
            throw new IOException("Original storage does not support the required conditional deletion contract");
        return generation;
    }

    /** Remove only the pinned live GCS object. Provider retention is separate from live-object absence. */
    public void deleteOriginal(UUID assetId, String expectedBucket, String key, String expectedGeneration) throws IOException {
        if (expectedGeneration == null || !expectedGeneration.matches("[1-9][0-9]*"))
            throw new IllegalStateException("A persisted original generation is required");
        String currentGeneration = deletionGeneration(assetId, expectedBucket, key);
        if ("0".equals(currentGeneration)) return; // a previous delete succeeded before its DB commit
        if (!currentGeneration.equals(expectedGeneration)) throw new IOException("The original generation changed");
        HttpResponse<String> response = send("DELETE", key, Map.of(),
                Map.of("x-goog-if-generation-match", expectedGeneration), "");
        if (response.statusCode() != 200 && response.statusCode() != 204 && response.statusCode() != 404) {
            throw new DeletionRequestException(response);
        }
        if (response.headers().firstValue("x-amz-delete-marker").map(Boolean::parseBoolean).orElse(false)
                || (response.body() != null && response.body().contains("<Error>"))) {
            throw new IOException("Object storage did not confirm removal of the live original");
        }
        if (send("HEAD", key, Map.of(), Map.of(), "").statusCode() != 404) {
            throw new IOException("Object storage did not confirm the live original is absent");
        }
    }

    private HttpResponse<String> send(String method, String key, Map<String, String> query,
                                      Map<String, String> headers, String body) throws IOException {
        String path = path(key);
        StringBuilder url = new StringBuilder(S3Signer.origin(endpoint)).append(S3Signer.encodePath(path));
        char separator = '?';
        for (Map.Entry<String, String> parameter : query.entrySet()) {
            url.append(separator).append(S3Signer.encode(parameter.getKey())).append('=')
                    .append(S3Signer.encode(parameter.getValue()));
            separator = '&';
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(60))
                .method(method, body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        signer.sign(method, endpoint, path, query, headers, S3Signer.sha256Hex(body), Instant.now())
                .forEach(request::header);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during " + method + " " + path, interrupted);
        }
    }

    private String path(String key) {
        return "/" + bucket + "/" + key;
    }

    private static String requireSuccess(HttpResponse<String> response, String action, String key) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException(action + " failed for " + key + ": HTTP " + response.statusCode() + " "
                    + brief(response.body()));
        }
        return response.body() == null ? "" : response.body();
    }

    private static String brief(String body) {
        String text = body == null ? "" : body.strip();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescape(String value) {
        return value.replace("&quot;", "\"").replace("&#34;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
