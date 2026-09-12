package site.yuqi.admin.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
@RequiredArgsConstructor
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final String OWNED = "(coalesce(metadata->>'source_type','') = '' and "
            + "coalesce(metadata->>'source','') not in ('Blogs','Projects','life_blogs','experience'))";
    private static final String REVISION = "md5(coalesce(content,'') || coalesce(metadata,'{}'::jsonb)::text)";

    public record Entry(UUID id, String content, Map<String, Object> metadata,
                        String createdAt, String revision, boolean editable) {}

    public Map<String, Object> list(String query, String scope, String status, int limit, int offset) {
        String where = " where (? = '' or strpos(lower(coalesce(content,'') || ' ' || coalesce(metadata->>'title','')"
                + " || ' ' || coalesce(metadata->>'question','')),lower(?)) > 0)"
                + " and (? = 'ALL' or coalesce(metadata->>'status','LEGACY') = ?)"
                + ("OWNED".equals(scope) ? " and " + OWNED : "INDEXED".equals(scope) ? " and not " + OWNED : "");
        Object[] filters = {query, query, status, status};
        long total = jdbc.queryForObject("select count(*) from public.kb_documents" + where, Long.class, filters);
        var args = new ArrayList<>(Arrays.asList(filters));
        args.add(limit); args.add(offset);
        List<Map<String, Object>> rows = jdbc.queryForList("select id::text, "
                + "coalesce(nullif(metadata->>'title',''),nullif(metadata->>'question',''),'Untitled knowledge') as title,"
                + "left(coalesce(content,''),180) as preview, coalesce(metadata->>'status','LEGACY') as status,"
                + "coalesce(metadata->>'source_type',metadata->>'type','NOTE') as \"sourceType\","
                + "coalesce(metadata->>'answer_visibility','private') as \"answerVisibility\","
                + OWNED + " as editable, created_at as \"createdAt\" from public.kb_documents"
                + where + " order by created_at desc, id limit ? offset ?", args.toArray());
        return Map.of("items", rows, "total", total, "limit", limit, "offset", offset);
    }

    public Optional<Entry> find(UUID id, boolean lock) {
        return jdbc.query("select id,content,metadata::text,created_at::text," + REVISION + " as revision,"
                + OWNED + " as editable from public.kb_documents where id=?" + (lock ? " for update" : ""),
                (rs, n) -> new Entry(rs.getObject("id", UUID.class), Objects.toString(rs.getString("content"), ""),
                        decode(rs.getString("metadata")), rs.getString("created_at"), rs.getString("revision"),
                        rs.getBoolean("editable")), id).stream().findFirst();
    }

    public Map<UUID, Entry> findBatch(List<UUID> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        var result = new LinkedHashMap<UUID, Entry>();
        jdbc.query("select id,content,metadata::text,created_at::text," + REVISION + " as revision,"
                + OWNED + " as editable from public.kb_documents where id in (" + placeholders + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    result.put(id, new Entry(id, Objects.toString(rs.getString("content"), ""), decode(rs.getString("metadata")),
                            rs.getString("created_at"), rs.getString("revision"), rs.getBoolean("editable")));
                }, ids.toArray());
        return result;
    }

    public Map<String, Map<String, Object>> indexingBatch(List<UUID> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        var result = new LinkedHashMap<String, Map<String, Object>>();
        jdbc.queryForList("select distinct on (source_id_text) source_id_text, id::text as \"jobId\",status,retry_count as \"retryCount\",last_error as \"lastError\""
                + " from public.indexing_jobs where source_type='OWNER_QA' and source_id_text in (" + placeholders + ")"
                + " order by source_id_text,created_at desc,id desc", ids.stream().map(UUID::toString).toArray())
                .forEach(row -> { String id = (String)row.remove("source_id_text"); result.put(id, row); });
        return result;
    }

    public boolean insert(UUID id, String content, Map<String, Object> metadata) {
        return jdbc.update("insert into public.kb_documents(id,content,metadata) values (?,?,?::jsonb) on conflict(id) do nothing",
                id, content, encode(metadata)) == 1;
    }

    public void update(UUID id, String content, Map<String, Object> metadata) {
        jdbc.update("update public.kb_documents set content=?,metadata=?::jsonb,embedding=null where id=?",
                content, encode(metadata), id);
    }

    public void invalidateDerived(UUID id) {
        jdbc.update("""
                update public.kb_documents set metadata=metadata || '{"status":"SUPERSEDED","retrieval_eligible":false}'::jsonb
                where metadata->>'source_id'=? and metadata->>'source_type' in ('OWNER_QA','PROFILE')
                """, id.toString());
    }

    public void delete(UUID id) {
        jdbc.update("delete from public.kb_documents where id=? or (metadata->>'source_id'=? and metadata->>'source_type' in ('OWNER_QA','PROFILE'))",
                id, id.toString());
    }

    public Map<String, Object> indexing(UUID id) {
        var jobs = jdbc.queryForList("""
                select id::text as "jobId",status,retry_count as "retryCount",last_error as "lastError"
                from public.indexing_jobs where source_type='OWNER_QA' and source_id_text=?
                order by created_at desc,id desc limit 1
                """, id.toString());
        return jobs.isEmpty() ? Map.of("status", "NOT_INDEXED") : jobs.get(0);
    }

    private Map<String, Object> decode(String value) {
        try { return value == null ? new LinkedHashMap<>() : mapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("Knowledge metadata is invalid"); }
    }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Knowledge metadata is invalid"); }
    }
}
