package site.yuqi.admin.adapter;

import org.springframework.stereotype.Component;
import site.yuqi.admin.domain.SourceType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry mapping {@link SourceType} -> {@link ContentAdapter}. Adapters are
 * collected by Spring component scan and indexed at construction time.
 */
@Component
public class ContentAdapterRegistry {

    private final Map<SourceType, ContentAdapter> adapters;

    public ContentAdapterRegistry(List<ContentAdapter> all) {
        this.adapters = new EnumMap<>(SourceType.class);
        for (ContentAdapter a : all) {
            ContentAdapter previous = adapters.put(a.sourceType(), a);
            if (previous != null) {
                throw new IllegalStateException("Duplicate adapter for " + a.sourceType());
            }
        }
        for (SourceType st : SourceType.values()) {
            if (!adapters.containsKey(st)) {
                throw new IllegalStateException("No adapter registered for " + st);
            }
        }
    }

    public ContentAdapter get(SourceType type) {
        ContentAdapter a = adapters.get(type);
        if (a == null) {
            throw new IllegalArgumentException("Unknown source type: " + type);
        }
        return a;
    }

    public List<ContentAdapter> all() {
        return List.copyOf(adapters.values());
    }
}
