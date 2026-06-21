package site.yuqi.admin.adapter;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class AdapterUtils {
    private static final Pattern TAG_SPLIT = Pattern.compile("[,;|]");

    private AdapterUtils() {}

    /**
     * Parse a free-form tag string into a non-null trimmed list. Returns
     * an empty list for blank input.
     */
    static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(TAG_SPLIT.split(raw))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    /** Join tags back to a comma-separated string for table columns of type text. */
    static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        return String.join(", ", tags);
    }

    /**
     * Linked map that preserves field order in snapshots and search documents.
     * Skips null values so the snapshot stays compact.
     */
    static Map<String, Object> compact(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("compact() requires key/value pairs");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object k = kv[i];
            Object v = kv[i + 1];
            if (v != null) out.put(String.valueOf(k), v);
        }
        return Collections.unmodifiableMap(out);
    }

    static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
