package site.yuqi.media.store;

import org.springframework.stereotype.Component;
import site.yuqi.media.config.MediaProperties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Path-style S3 client over the JDK HTTP client: {@code {endpoint}/{bucket}/{key}}.
 * Works unchanged against Google Cloud Storage, Cloudflare R2, Amazon S3 and MinIO.
 */
@Component
public class S3ObjectStore implements ObjectStore {

    private static final int ATTEMPTS = 4;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final URI endpoint;
    private final S3Signer signer;

    public S3ObjectStore(MediaProperties properties) {
        MediaProperties.Storage storage = properties.storage();
        this.endpoint = URI.create(storage.endpoint().replaceAll("/+$", ""));
        this.signer = new S3Signer(storage.accessKey(), storage.secretKey(), storage.region());
    }

    @Override
    public Optional<ObjectInfo> head(String bucket, String key) throws IOException {
        HttpResponse<InputStream> response = send("HEAD", bucket, key, Map.of(),
                () -> HttpRequest.BodyPublishers.noBody(), S3Signer.EMPTY_SHA256);
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) return Optional.empty();
            requireSuccess(response, body, "HEAD", bucket, key);
            return Optional.of(new ObjectInfo(
                    response.headers().firstValueAsLong("Content-Length").orElse(-1),
                    response.headers().firstValue("ETag").orElse(""),
                    response.headers().firstValue("Content-Type").orElse("")));
        }
    }

    @Override
    public void download(String bucket, String key, Path target) throws IOException {
        HttpResponse<InputStream> response = send("GET", bucket, key, Map.of(),
                () -> HttpRequest.BodyPublishers.noBody(), S3Signer.EMPTY_SHA256);
        try (InputStream body = response.body()) {
            requireSuccess(response, body, "GET", bucket, key);
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    @Override
    public void upload(String bucket, String key, Path file, String contentType, String cacheControl)
            throws IOException {
        put(bucket, key, contentType, cacheControl, () -> {
            try {
                return HttpRequest.BodyPublishers.ofFile(file);
            } catch (IOException missing) {
                throw new IllegalStateException(missing);
            }
        });
    }

    @Override
    public void upload(String bucket, String key, byte[] body, String contentType, String cacheControl)
            throws IOException {
        put(bucket, key, contentType, cacheControl, () -> HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private void put(String bucket, String key, String contentType, String cacheControl,
                     Supplier<HttpRequest.BodyPublisher> body) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        if (cacheControl != null) headers.put("Cache-Control", cacheControl);
        // The body is not hashed into the signature: outputs can be large and the connection is TLS.
        HttpResponse<InputStream> response = send("PUT", bucket, key, headers, body, S3Signer.UNSIGNED_PAYLOAD);
        try (InputStream responseBody = response.body()) {
            requireSuccess(response, responseBody, "PUT", bucket, key);
        }
    }

    /** One request, retried on connection trouble, throttling and 5xx. Client errors are final. */
    private HttpResponse<InputStream> send(String method, String bucket, String key, Map<String, String> headers,
                                           Supplier<HttpRequest.BodyPublisher> body, String payloadHash)
            throws IOException {
        String path = "/" + bucket + "/" + key;
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(
                                URI.create(S3Signer.origin(endpoint) + S3Signer.encodePath(path)))
                        .timeout(Duration.ofMinutes(10))
                        .method(method, body.get());
                headers.forEach(request::header);
                // Only host and the x-amz-* headers are signed; Content-Type and Cache-Control ride along.
                signer.sign(method, endpoint, path, Map.of(), Map.of(), payloadHash, Instant.now())
                        .forEach(request::header);
                HttpResponse<InputStream> response = http.send(request.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    try (InputStream discard = response.body()) {
                        last = new ObjectStoreException(method, bucket, key, status, read(discard));
                    }
                } else {
                    return response;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted during " + method + " " + path, interrupted);
            } catch (IOException network) {
                last = network;
            }
            sleep(250L << attempt);
        }
        throw last;
    }

    private static void requireSuccess(HttpResponse<InputStream> response, InputStream body,
                                       String method, String bucket, String key) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new ObjectStoreException(method, bucket, key, response.statusCode(), read(body));
        }
    }

    private static String read(InputStream body) {
        try {
            byte[] bytes = body.readNBytes(2048);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while backing off", interrupted);
        }
    }

    /** The store answered, and the answer was no. */
    public static final class ObjectStoreException extends IOException {
        private final int status;

        ObjectStoreException(String method, String bucket, String key, int status, String body) {
            super(method + " " + bucket + "/" + key + " -> HTTP " + status
                    + (body == null || body.isBlank() ? "" : ": " + body.strip()));
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
