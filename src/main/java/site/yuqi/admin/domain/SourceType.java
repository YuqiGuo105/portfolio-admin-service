package site.yuqi.admin.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Logical content sources owned by the admin platform. The backing tables are
 * heterogeneous (see {@link site.yuqi.admin.domain.source}). Adapters normalize
 * them via {@link site.yuqi.admin.adapter.ContentAdapter}.
 */
public enum SourceType {
    BLOG,
    PROJECT,
    LIFE_BLOG,
    EXPERIENCE;

    public static Optional<SourceType> parse(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(raw))
                .findFirst();
    }
}
