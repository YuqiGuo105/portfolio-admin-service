package site.yuqi.ragindexer.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads source rows from Supabase Postgres and returns a {@link RagSource}
 * containing the long-form text suitable for embedding.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentFetcher {

    private final JdbcTemplate jdbc;

    public Optional<RagSource> fetch(String sourceType, String sourceId) {
        return switch (sourceType) {
            case "BLOG"       -> fetchBlog(sourceId);
            case "PROJECT"    -> fetchProject(sourceId);
            case "LIFE_BLOG"  -> fetchLifeBlog(sourceId);
            case "EXPERIENCE" -> fetchExperience(sourceId);
            default -> {
                log.warn("Unknown sourceType '{}'", sourceType);
                yield Optional.empty();
            }
        };
    }

    private Optional<RagSource> fetchBlog(String id) {
        String sql = """
            SELECT id, title, description, content
              FROM public."Blogs" WHERE id = ?
            """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, n) -> RagSource.builder()
                    .sourceType("BLOG")
                    .sourceId(rs.getObject("id", UUID.class).toString())
                    .title(rs.getString("title"))
                    .summary(rs.getString("description"))
                    .content(rs.getString("content"))
                    .url("/blog-single/" + rs.getObject("id", UUID.class))
                    .build(), UUID.fromString(id)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Optional<RagSource> fetchProject(String id) {
        String sql = """
            SELECT id, title, content
              FROM public."Projects" WHERE id = ?
            """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, n) -> RagSource.builder()
                    .sourceType("PROJECT")
                    .sourceId(rs.getObject("id", UUID.class).toString())
                    .title(rs.getString("title"))
                    .content(rs.getString("content"))
                    .url("/work-single/" + rs.getObject("id", UUID.class))
                    .build(), UUID.fromString(id)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Optional<RagSource> fetchLifeBlog(String id) {
        String sql = """
            SELECT id, title, description
              FROM public.life_blogs WHERE id = ?
            """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, n) -> {
                long lid = rs.getLong("id");
                return RagSource.builder()
                        .sourceType("LIFE_BLOG")
                        .sourceId(String.valueOf(lid))
                        .title(rs.getString("title"))
                        .summary(rs.getString("description"))
                        .content(rs.getString("description")) // life_blogs has no long body — fall back to description
                        .url("/life-blog/" + lid)
                        .build();
            }, Long.parseLong(id)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Optional<RagSource> fetchExperience(String id) {
        String sql = """
            SELECT id, name, subname, "text" AS body
              FROM public.experience WHERE id = ?
            """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, n) -> {
                long eid = rs.getLong("id");
                return RagSource.builder()
                        .sourceType("EXPERIENCE")
                        .sourceId(String.valueOf(eid))
                        .title(rs.getString("name"))
                        .summary(rs.getString("subname"))
                        .content(rs.getString("body"))
                        .url("/cv#exp-" + eid)
                        .build();
            }, Long.parseLong(id)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
