package site.yuqi.searchindexer.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads source rows from Supabase Postgres using raw JDBC (the source schema
 * mixes quoted PascalCase identifiers and snake_case, so JdbcTemplate is the
 * lightest way to avoid duplicating the admin-service JPA entities here).
 *
 * <p>Each fetcher method returns the OpenSearch document map directly, or
 * {@link Optional#empty()} if the source row no longer exists (deleted).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentFetcher {

    private final JdbcTemplate jdbc;

    /** Looks up a source row and returns a document Map ready for OpenSearch. */
    public Optional<Map<String, Object>> fetchSearchDocument(String sourceType, String sourceId) {
        return switch (sourceType) {
            case "BLOG"       -> fetchBlog(sourceId);
            case "PROJECT"    -> fetchProject(sourceId);
            case "LIFE_BLOG"  -> fetchLifeBlog(sourceId);
            case "EXPERIENCE" -> fetchExperience(sourceId);
            default -> {
                log.warn("Unknown sourceType '{}' — skipping", sourceType);
                yield Optional.empty();
            }
        };
    }

    // ---- BLOG -------------------------------------------------------------

    private Optional<Map<String, Object>> fetchBlog(String sourceId) {
        String sql = """
            SELECT id, title, description, content, category, tags, image_url, date
              FROM public."Blogs"
             WHERE id = ?
            """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, n) -> {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("id", "BLOG:" + rs.getObject("id", UUID.class));
                doc.put("type", "BLOG");
                doc.put("title", rs.getString("title"));
                doc.put("summary", rs.getString("description"));
                doc.put("body", plainText(rs.getString("content")));
                doc.put("category", rs.getString("category"));
                doc.put("tags", parseTags(rs.getString("tags")));
                doc.put("imageUrl", rs.getString("image_url"));
                doc.put("url", "/blog-single/" + rs.getObject("id", UUID.class));
                return doc;
            }, UUID.fromString(sourceId)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ---- PROJECT ----------------------------------------------------------

    private Optional<Map<String, Object>> fetchProject(String sourceId) {
        String sql = """
            SELECT id, title, summary, content, category, year, technology, image_url, "URL" AS external_url
              FROM public."Projects"
             WHERE id = ?
            """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, n) -> {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("id", "PROJECT:" + rs.getObject("id", UUID.class));
                doc.put("type", "PROJECT");
                doc.put("title", rs.getString("title"));
                doc.put("summary", rs.getString("summary"));
                doc.put("body", plainText(rs.getString("content")));
                doc.put("category", rs.getString("category"));
                doc.put("tags", parseTags(rs.getString("technology")));
                doc.put("imageUrl", rs.getString("image_url"));
                doc.put("externalUrl", rs.getString("external_url"));
                doc.put("year", rs.getString("year"));
                doc.put("url", "/work-single/" + rs.getObject("id", UUID.class));
                return doc;
            }, UUID.fromString(sourceId)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ---- LIFE_BLOG (numeric id) ------------------------------------------

    private Optional<Map<String, Object>> fetchLifeBlog(String sourceId) {
        String sql = """
            SELECT id, title, description, category, tags, image_url, published_at
              FROM public.life_blogs
             WHERE id = ?
            """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, n) -> {
                long id = rs.getLong("id");
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("id", "LIFE_BLOG:" + id);
                doc.put("type", "LIFE_BLOG");
                doc.put("title", rs.getString("title"));
                doc.put("summary", rs.getString("description"));
                doc.put("category", rs.getString("category"));
                doc.put("tags", parseTags(rs.getString("tags")));
                doc.put("imageUrl", rs.getString("image_url"));
                doc.put("url", "/life-blog/" + id);
                return doc;
            }, Long.parseLong(sourceId)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ---- EXPERIENCE (numeric id) -----------------------------------------

    private Optional<Map<String, Object>> fetchExperience(String sourceId) {
        String sql = """
            SELECT id, name, subname, "text" AS body, date
              FROM public.experience
             WHERE id = ?
            """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, n) -> {
                long id = rs.getLong("id");
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("id", "EXPERIENCE:" + id);
                doc.put("type", "EXPERIENCE");
                doc.put("title", rs.getString("name"));
                doc.put("summary", rs.getString("subname"));
                doc.put("body", rs.getString("body"));
                doc.put("year", rs.getString("date"));
                doc.put("url", "/cv#exp-" + id);
                return doc;
            }, Long.parseLong(sourceId)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.split("\\s*,\\s*"));
    }

    private static String plainText(String value) {
        return value == null ? null : Jsoup.parse(value).text();
    }
}
