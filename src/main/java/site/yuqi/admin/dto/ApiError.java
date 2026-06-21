package site.yuqi.admin.dto;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        String error,
        String message,
        Instant timestamp,
        Map<String, Object> details
) {
    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now(), Map.of());
    }
    public static ApiError of(String error, String message, Map<String, Object> details) {
        return new ApiError(error, message, Instant.now(), details == null ? Map.of() : details);
    }
}
