package site.yuqi.admin.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import site.yuqi.admin.dto.ContentCoverUploadRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CoverImageLoader {

    private final HttpClient httpClient;
    private final int maxBytes;
    private final Duration requestTimeout;
    private final Set<String> allowedSourceHosts;

    public CoverImageLoader(
            @Value("${portfolio.media.max-cover-bytes:5242880}") int maxBytes,
            @Value("${portfolio.media.download-timeout-seconds:10}") int timeoutSeconds,
            @Value("${portfolio.media.allowed-source-hosts:}") String allowedSourceHosts) {
        this.maxBytes = maxBytes;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.allowedSourceHosts = Arrays.stream(allowedSourceHosts.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 5)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public LoadedCover load(ContentCoverUploadRequest request) {
        if (request == null) throw new IllegalArgumentException("Cover upload body is required.");
        boolean hasUrl = request.getSourceUrl() != null && !request.getSourceUrl().isBlank();
        boolean hasBase64 = request.getImageBase64() != null && !request.getImageBase64().isBlank();
        if (hasUrl == hasBase64) {
            throw new IllegalArgumentException("Provide exactly one of sourceUrl or imageBase64.");
        }

        RawImage raw = hasUrl ? download(request.getSourceUrl()) : decode(request.getImageBase64());
        String detectedMime = detectMime(raw.bytes());
        String claimedMime = normalizeMime(firstNonBlank(request.getMimeType(), raw.claimedMime()));
        if (claimedMime != null && !claimedMime.equals(detectedMime)) {
            throw new IllegalArgumentException(
                    "Image MIME type does not match its file signature (claimed "
                            + claimedMime + ", detected " + detectedMime + ").");
        }
        return new LoadedCover(raw.bytes(), detectedMime, extensionFor(detectedMime));
    }

    private RawImage decode(String encoded) {
        String payload = encoded.trim();
        String claimedMime = null;
        if (payload.startsWith("data:")) {
            int comma = payload.indexOf(',');
            if (comma < 0 || !payload.substring(0, comma).toLowerCase(Locale.ROOT).endsWith(";base64")) {
                throw new IllegalArgumentException("Only base64 image data URIs are supported.");
            }
            claimedMime = payload.substring(5, payload.indexOf(';', 5));
            payload = payload.substring(comma + 1);
        }
        if (payload.length() > ((long) maxBytes * 4 / 3) + 16) {
            throw new IllegalArgumentException("Cover image exceeds the configured size limit.");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            validateSize(bytes.length);
            return new RawImage(bytes, claimedMime);
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("size limit")) throw ex;
            throw new IllegalArgumentException("imageBase64 is not valid base64 data.", ex);
        }
    }

    private RawImage download(String sourceUrl) {
        URI uri;
        try {
            uri = URI.create(sourceUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("sourceUrl is not a valid URI.", ex);
        }
        validatePublicHttpsUri(uri);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "image/png,image/jpeg,image/webp")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IllegalArgumentException("Image source returned HTTP " + response.statusCode() + ".");
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxBytes) {
                response.body().close();
                throw new IllegalArgumentException("Cover image exceeds the configured size limit.");
            }
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(maxBytes + 1);
                validateSize(bytes.length);
                String contentType = response.headers().firstValue("Content-Type").orElse(null);
                return new RawImage(bytes, contentType);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Image download was interrupted.", ex);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to download the cover image.", ex);
        }
    }

    private void validatePublicHttpsUri(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("sourceUrl must be a public HTTPS URL without credentials.");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("sourceUrl must use the default HTTPS port.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = allowedSourceHosts.stream()
                .anyMatch(value -> host.equals(value) || host.endsWith("." + value));
        if (!allowed) {
            throw new IllegalArgumentException(
                    "sourceUrl host is not in the configured content-cover allowlist; use imageBase64 instead.");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                byte[] bytes = address.getAddress();
                boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress() || uniqueLocalV6) {
                    throw new IllegalArgumentException("sourceUrl resolves to a non-public address.");
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("sourceUrl host could not be resolved.", ex);
        }
    }

    private void validateSize(int size) {
        if (size == 0) throw new IllegalArgumentException("Cover image is empty.");
        if (size > maxBytes) throw new IllegalArgumentException("Cover image exceeds the configured size limit.");
    }

    static String detectMime(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        throw new IllegalArgumentException("Unsupported image format. Use PNG, JPEG, or WebP.");
    }

    private static String extensionFor(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("Unsupported image MIME type: " + mimeType);
        };
    }

    private static String normalizeMime(String value) {
        if (value == null || value.isBlank()) return null;
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    public record LoadedCover(byte[] bytes, String mimeType, String extension) {}
    private record RawImage(byte[] bytes, String claimedMime) {}
}
