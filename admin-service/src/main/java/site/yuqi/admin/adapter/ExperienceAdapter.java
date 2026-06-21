package site.yuqi.admin.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.domain.source.Experience;
import site.yuqi.admin.repo.source.ExperienceRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ExperienceAdapter implements ContentAdapter {

    private static final String CATEGORY = "Experience";

    private final ExperienceRepository repository;

    @Override
    public SourceType sourceType() {
        return SourceType.EXPERIENCE;
    }

    @Override
    public List<NormalizedContent> list(String keyword, String category, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        return repository.search(blank(keyword), PageRequest.of(safeOffset / safeLimit, safeLimit))
                .stream().map(this::normalize).toList();
    }

    @Override
    public Optional<NormalizedContent> get(String sourceId) {
        return repository.findById(Long.parseLong(sourceId)).map(this::normalize);
    }

    @Override
    public NormalizedContent create(Map<String, Object> input) {
        Experience row = Experience.builder()
                .name(str(input, "title", "name"))
                .subname(str(input, "summary", "subname"))
                .text(str(input, "content", "text"))
                .date(str(input, "date"))
                .build();
        return normalize(repository.save(row));
    }

    @Override
    public NormalizedContent update(String sourceId, Map<String, Object> input) {
        Experience row = repository.findById(Long.parseLong(sourceId))
                .orElseThrow(() -> new IllegalArgumentException("EXPERIENCE not found: " + sourceId));
        if (input.containsKey("title") || input.containsKey("name"))
            row.setName(str(input, "title", "name"));
        if (input.containsKey("summary") || input.containsKey("subname"))
            row.setSubname(str(input, "summary", "subname"));
        if (input.containsKey("content") || input.containsKey("text"))
            row.setText(str(input, "content", "text"));
        if (input.containsKey("date")) row.setDate(str(input, "date"));
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
                "url", c.getUrl());
    }

    @Override
    public String toRagText(NormalizedContent c) {
        StringBuilder sb = new StringBuilder();
        if (c.getTitle() != null) sb.append(c.getTitle()).append("\n");
        if (c.getSummary() != null) sb.append(c.getSummary()).append("\n");
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
                "category", CATEGORY,
                "url", c.getUrl());
    }

    @Override
    public String toUrl(NormalizedContent c) {
        return "/#resume";
    }

    // --- helpers ---------------------------------------------------------

    private NormalizedContent normalize(Experience e) {
        NormalizedContent c = NormalizedContent.builder()
                .sourceType(SourceType.EXPERIENCE)
                .sourceId(String.valueOf(e.getId()))
                .title(e.getName())
                .summary(e.getSubname())
                .content(e.getText())
                .category(CATEGORY)
                .tags(List.of())
                .imageUrl(null)
                .raw(AdapterUtils.compact(
                        "id", e.getId(),
                        "date", e.getDate(),
                        "name", e.getName(),
                        "subname", e.getSubname()))
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
}
