package site.yuqi.admin.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.domain.source.Project;
import site.yuqi.admin.repo.source.ProjectRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProjectAdapter implements ContentAdapter {

    private final ProjectRepository repository;

    @Override
    public SourceType sourceType() {
        return SourceType.PROJECT;
    }

    @Override
    public List<NormalizedContent> list(String keyword, String category, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        return repository.search(blank(keyword), blank(category),
                        PageRequest.of(safeOffset / safeLimit, safeLimit))
                .stream().map(this::normalize).toList();
    }

    @Override
    public Optional<NormalizedContent> get(String sourceId) {
        return repository.findById(UUID.fromString(sourceId)).map(this::normalize);
    }

    @Override
    public NormalizedContent create(Map<String, Object> input) {
        Project row = Project.builder()
                .id(UUID.randomUUID())
                .title(str(input, "title"))
                .content(str(input, "content", "summary"))
                .category(str(input, "category"))
                .year(str(input, "year"))
                .technology(techToString(input))
                .imageUrl(str(input, "imageUrl", "image_url"))
                .externalUrl(str(input, "externalUrl", "URL"))
                .num(intVal(input, "num"))
                .publishedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return normalize(repository.save(row));
    }

    @Override
    public NormalizedContent update(String sourceId, Map<String, Object> input) {
        Project row = repository.findById(UUID.fromString(sourceId))
                .orElseThrow(() -> new IllegalArgumentException("PROJECT not found: " + sourceId));
        if (input.containsKey("title"))       row.setTitle(str(input, "title"));
        if (input.containsKey("content"))     row.setContent(str(input, "content"));
        if (input.containsKey("summary"))     row.setContent(str(input, "summary"));
        if (input.containsKey("category"))    row.setCategory(str(input, "category"));
        if (input.containsKey("year"))        row.setYear(str(input, "year"));
        if (input.containsKey("technology") || input.containsKey("tags"))
            row.setTechnology(techToString(input));
        if (input.containsKey("imageUrl") || input.containsKey("image_url"))
            row.setImageUrl(str(input, "imageUrl", "image_url"));
        if (input.containsKey("externalUrl") || input.containsKey("URL"))
            row.setExternalUrl(str(input, "externalUrl", "URL"));
        if (input.containsKey("num"))         row.setNum(intVal(input, "num"));
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
                "url", c.getUrl(),
                "externalUrl", c.rawOrEmpty().get("URL"));
    }

    @Override
    public String toRagText(NormalizedContent c) {
        StringBuilder sb = new StringBuilder();
        if (c.getTitle() != null) sb.append(c.getTitle()).append("\n\n");
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
                "url", c.getUrl(),
                "externalUrl", c.rawOrEmpty().get("URL"));
    }

    @Override
    public String toUrl(NormalizedContent c) {
        return "/work-single/" + c.getSourceId();
    }

    // --- helpers ---------------------------------------------------------

    private NormalizedContent normalize(Project p) {
        NormalizedContent c = NormalizedContent.builder()
                .sourceType(SourceType.PROJECT)
                .sourceId(p.getId().toString())
                .title(p.getTitle())
                .summary(p.getContent())   // PROJECT has no separate summary column
                .content(p.getContent())
                .category(p.getCategory())
                .tags(AdapterUtils.parseTags(p.getTechnology()))
                .imageUrl(p.getImageUrl())
                .raw(AdapterUtils.compact(
                        "id", p.getId().toString(),
                        "category", p.getCategory(),
                        "year", p.getYear(),
                        "technology", p.getTechnology(),
                        "image_url", p.getImageUrl(),
                        "URL", p.getExternalUrl(),
                        "num", p.getNum(),
                        "published_at", p.getPublishedAt(),
                        "updated_at", p.getUpdatedAt()))
                .build();
        c.setUrl(toUrl(c));
        return c;
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s; }

    private static String str(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return String.valueOf(v);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String techToString(Map<String, Object> m) {
        Object t = m.get("technology");
        if (t == null) t = m.get("tags");
        if (t instanceof List<?>) return AdapterUtils.joinTags((List<String>) t);
        if (t instanceof String s) return s;
        return null;
    }

    private static Integer intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }
}
