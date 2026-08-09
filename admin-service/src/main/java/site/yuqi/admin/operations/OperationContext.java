package site.yuqi.admin.operations;

import jakarta.servlet.http.HttpServletRequest;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/** Request-scoped identifiers propagated across HTTP, Kafka, and async projections. */
public record OperationContext(String traceId, String correlationId, String actorId) {

    private static final ThreadLocal<OperationContext> CURRENT = new ThreadLocal<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static OperationContext from(HttpServletRequest request) {
        String traceId = traceId(request.getHeader("traceparent"));
        String correlationId = firstNonBlank(
                request.getHeader("X-Correlation-Id"),
                request.getHeader("X-Request-Id"),
                UUID.randomUUID().toString());
        // Identity comes from the auth layer; never trust a caller-supplied actor header.
        String actorId = "authenticated-admin";
        return new OperationContext(traceId, correlationId, actorId);
    }

    public static OperationContext current() {
        OperationContext value = CURRENT.get();
        return value != null ? value : new OperationContext(randomHex(16), UUID.randomUUID().toString(), "system");
    }

    public static void set(OperationContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static String traceId(String traceparent) {
        if (traceparent != null) {
            String[] parts = traceparent.trim().split("-");
            if (parts.length == 4 && parts[1].matches("[0-9a-fA-F]{32}") && !parts[1].matches("0{32}")) {
                return parts[1].toLowerCase();
            }
        }
        return randomHex(16);
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        throw new IllegalArgumentException("At least one fallback value is required");
    }
}
