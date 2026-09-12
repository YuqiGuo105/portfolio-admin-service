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
            case "OWNER_QA"   -> fetchKnowledge(sourceId);
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

    private Optional<RagSource> fetchKnowledge(String id) {
        return jdbc.query("""
                select id,content,md5(content) as content_md5,metadata->>'title' as title,
                       metadata->>'question' as question,(metadata->>'management_version')::int as version
                from public.kb_documents where id=? and metadata->>'type'='chat_qa'
                  and metadata->>'status'='ACTIVE' and metadata->>'answer_visibility'='public'
                  and metadata->>'evidence_review'='approved'
                  and coalesce(metadata->>'source_type','')=''
                """, (rs, n) -> RagSource.builder().sourceType("OWNER_QA").sourceId(id)
                .title(rs.getString("title")).summary(rs.getString("question")).content(rs.getString("content"))
                .url("").knowledgeVersion(rs.getInt("version")).originalContentMd5(rs.getString("content_md5"))
                .build(), UUID.fromString(id)).stream().findFirst();
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
            SELECT id, title, summary, content
              FROM public."Projects" WHERE id = ?
            """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, n) -> RagSource.builder()
                    .sourceType("PROJECT")
                    .sourceId(rs.getObject("id", UUID.class).toString())
                    .title(rs.getString("title"))
                    .summary(rs.getString("summary"))
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
