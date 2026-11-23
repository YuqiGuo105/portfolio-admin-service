package site.yuqi.media.store;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4, the request signing every S3-compatible store accepts:
 * Amazon S3, Google Cloud Storage (XML API with HMAC keys), Cloudflare R2, MinIO.
 *
 * <p>Kept dependency-free on purpose. The same class exists in the Content Service; the two
 * services share a storage contract, not a library.
 */
public final class S3Signer {

    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final String accessKey;
    private final String secretKey;
    private final String region;

    public S3Signer(String accessKey, String secretKey, String region) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    /**
     * A URL that carries its own authorisation in the query string. Whoever holds it can make
     * exactly this request until it expires, without any credentials.
     *
     * @param path  unencoded absolute path, e.g. {@code /bucket/key with spaces.mp4}
     * @param query extra query parameters, e.g. partNumber and uploadId
     */
    public URI presign(String method, URI endpoint, String path, Map<String, String> query,
                       Duration expires, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String scope = DATE.format(now) + "/" + region + "/s3/aws4_request";

        TreeMap<String, String> all = new TreeMap<>(query);
        all.put("X-Amz-Algorithm", ALGORITHM);
        all.put("X-Amz-Credential", accessKey + "/" + scope);
        all.put("X-Amz-Date", amzDate);
        all.put("X-Amz-Expires", Long.toString(expires.toSeconds()));
        all.put("X-Amz-SignedHeaders", "host");

        String canonicalQuery = canonicalQuery(all);
        String canonicalRequest = method + "\n"
                + encodePath(path) + "\n"
                + canonicalQuery + "\n"
                + "host:" + hostHeader(endpoint) + "\n\n"
                + "host\n"
                + UNSIGNED_PAYLOAD;
        String signature = signature(canonicalRequest, amzDate, scope, now);
        return URI.create(origin(endpoint) + encodePath(path) + "?" + canonicalQuery
                + "&X-Amz-Signature=" + signature);
    }

    /**
     * Headers that authorise one server-side request. The caller must send every header in
     * {@code headers} unchanged, plus everything this method returns.
     *
     * @param payloadHash hex SHA-256 of the body, or {@link #UNSIGNED_PAYLOAD}
     */
    public Map<String, String> sign(String method, URI endpoint, String path, Map<String, String> query,
                                    Map<String, String> headers, String payloadHash, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String scope = DATE.format(now) + "/" + region + "/s3/aws4_request";

        TreeMap<String, String> signed = new TreeMap<>();
        headers.forEach((name, value) -> signed.put(name.toLowerCase(Locale.ROOT), value.trim()));
        signed.put("host", hostHeader(endpoint));
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);

        StringBuilder canonicalHeaders = new StringBuilder();
        signed.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
        String signedHeaders = String.join(";", signed.keySet());

        String canonicalRequest = method + "\n"
                + encodePath(path) + "\n"
                + canonicalQuery(new TreeMap<>(query)) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String signature = signature(canonicalRequest, amzDate, scope, now);

        Map<String, String> out = new LinkedHashMap<>();
        out.put("x-amz-content-sha256", payloadHash);
        out.put("x-amz-date", amzDate);
        out.put("Authorization", ALGORITHM + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return out;
    }

    private String signature(String canonicalRequest, String amzDate, String scope, Instant now) {
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), DATE.format(now));
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        return HexFormat.of().formatHex(hmac(key, stringToSign));
    }

    /** scheme://host[:port], with no trailing slash. */
    public static String origin(URI endpoint) {
        return endpoint.getScheme() + "://" + hostHeader(endpoint);
    }

    /** The Host header a client will send: the port is left out when it is the scheme default. */
    static String hostHeader(URI endpoint) {
        int port = endpoint.getPort();
        boolean defaultPort = port == -1
                || ("https".equalsIgnoreCase(endpoint.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(endpoint.getScheme()) && port == 80);
        return defaultPort ? endpoint.getHost() : endpoint.getHost() + ":" + port;
    }

    private static String canonicalQuery(TreeMap<String, String> query) {
        StringBuilder out = new StringBuilder();
        query.forEach((name, value) -> {
            if (out.length() > 0) out.append('&');
            out.append(encode(name)).append('=').append(encode(value));
        });
        return out.toString();
    }

    /** Percent-encode every segment once; the slashes between segments stay. */
    public static String encodePath(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            out.append(encode(segments[i]));
        }
        String encoded = out.toString();
        return encoded.startsWith("/") ? encoded : "/" + encoded;
    }

    /** RFC 3986: letters, digits, '-', '_', '.', '~' stay; everything else becomes %XX. */
    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) out.append((char) c);
            else out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
        }
        return out.toString();
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
