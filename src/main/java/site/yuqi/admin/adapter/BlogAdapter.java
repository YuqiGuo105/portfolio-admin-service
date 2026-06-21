package site.yuqi.admin.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.domain.source.Blog;
import site.yuqi.admin.repo.source.BlogRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class BlogAdapter implements ContentAdapter {

    private final BlogRepository repository;

    @Override
    public SourceType sourceType() {
        return SourceType.BLOG;
    }

    @Override
    public List<NormalizedContent> list(String keyword, String category, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        return repository
                .search(blank(keyword), blank(category), PageRequest.of(safeOffset / safeLimit, safeLimit))
                .stream().map(this::normalize).toList();
    }

    @Override
    public Optional<NormalizedContent> get(String sourceId) {
        return repository.findById(UUID.fromString(sourceId)).map(this::normalize);
    }

    @Override
    public NormalizedContent create(Map<String, Object> input) {
        Blog row = Blog.builder()
                .id(UUID.randomUUID())
                .title(str(input, "title"))
                .description(str(input, "summary", "description"))
                .content(str(input, "content"))
                .category(str(input, "category"))
                .tags(tagsToString(input))
                .imageUrl(str(input, "imageUrl", "image_url"))
                .date(str(input, "date"))
                .build();
        return normalize(repository.save(row));
    }

    @Override
    public NormalizedContent update(String sourceId, Map<String, Object> input) {
        Blog row = repository.findById(UUID.fromString(sourceId))
                .orElseThrow(() -> new IllegalArgumentException("BLOG not found: " + sourceId));
        if (input.containsKey("title"))      row.setTitle(str(input, "title"));
        if (input.containsKey("summary"))    row.setDescription(str(input, "summary"));
        if (input.containsKey("description"))row.setDescription(str(input, "description"));
        if (input.containsKey("content"))    row.setContent(str(input, "content"));
        if (input.containsKey("category"))   row.setCategory(str(input, "category"));
        if (input.containsKey("tags"))       row.setTags(tagsToString(input));
        if (input.containsKey("imageUrl") || input.containsKey("image_url"))
            row.setImageUrl(str(input, "imageUrl", "image_url"));
        if (input.containsKey("date"))       row.setDate(str(input, "date"));
        return normalize(repository.save(row));
    }

    @Override
    public Map<String, Object> toSnapshot(NormalizedContent c) {
        return AdapterUtils.compact(
                "sourceType", c.getSourceType().name(),
                "sourceId", c.getSourceId(),
                "title", c.getTitle(),
                "summary", c.getSummary(),
                "content", c.getContent(),
                "category", c.getCategory(),
                "tags", c.getTags(),
                "imageUrl", c.getImageUrl(),
                "url", c.getUrl());
    }

    @Override
    public String toRagText(NormalizedContent c) {
        StringBuilder sb = new StringBuilder();
        if (c.getTitle() != null) sb.append(c.getTitle()).append("\n\n");
        if (c.getSummary() != null) sb.append(c.getSummary()).append("\n\n");
        if (c.getContent() != null) sb.append(c.getContent());
        return sb.toString();
    }

    @Override
    public Map<String, Object> toSearchDocument(NormalizedContent c) {
        return AdapterUtils.compact(
                "id", c.getSourceType().name() + ":" + c.getSourceId(),
                "type", c.getSourceType().name(),
                "title", c.getTitle(),
                "summary", c.getSummary(),
                "body", c.getContent(),
                "category", c.getCategory(),
                "tags", c.getTags(),
                "imageUrl", c.getImageUrl(),
                "url", c.getUrl());
    }

    @Override
    public String toUrl(NormalizedContent c) {
        return "/blog-single/" + c.getSourceId();
    }

    // --- helpers -----------------------------------------------------------

    private NormalizedContent normalize(Blog b) {
        NormalizedContent c = NormalizedContent.builder()
                .sourceType(SourceType.BLOG)
                .sourceId(b.getId().toString())
                .title(b.getTitle())
                .summary(b.getDescription())
                .content(b.getContent())
                .category(b.getCategory())
                .tags(AdapterUtils.parseTags(b.getTags()))
                .imageUrl(b.getImageUrl())
                .raw(AdapterUtils.compact(
                        "id", b.getId().toString(),
                        "date", b.getDate(),
                        "category", b.getCategory(),
                        "image_url", b.getImageUrl(),
                        "tags", b.getTags()))
                .build();
        c.setUrl(toUrl(c));
        return c;
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String str(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return String.valueOf(v);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String tagsToString(Map<String, Object> m) {
        Object v = m.get("tags");
        if (v instanceof List<?>) return AdapterUtils.joinTags((List<String>) v);
        if (v instanceof String s) return s;
        return null;
    }
}
