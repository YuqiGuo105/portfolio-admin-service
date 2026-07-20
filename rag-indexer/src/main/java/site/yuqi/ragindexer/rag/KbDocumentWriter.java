package site.yuqi.ragindexer.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.ragindexer.source.RagSource;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes RAG chunks (content + embedding + metadata) to {@code public.kb_documents}.
 *
 * <p>Uses raw JDBC because JPA cannot bind the {@code pgvector} type — values
 * are inserted as the literal {@code [0.1,0.2,...]} text representation and
 * cast with {@code ?::vector}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbDocumentWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Mark all currently-ACTIVE chunks for this source as SUPERSEDED. New
     * chunks then come in as ACTIVE in the same transaction.
     */
    @Transactional
    public void supersedeAndInsert(RagSource source,
                                   int sourceVersion,
                                   java.util.List<String> chunks,
                                   java.util.List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunk count != embedding count");
        }

        // Replaying the same Kafka event must replace, not accumulate, the
        // same source version. This delete and the inserts share one DB tx.
        jdbc.update("""
                DELETE FROM public.kb_documents
                 WHERE metadata->>'source_type' = ?
                   AND metadata->>'source_id' = ?
                   AND metadata->>'source_version' = ?
                """, source.getSourceType(), source.getSourceId(), String.valueOf(sourceVersion));

        jdbc.update("""
                UPDATE public.kb_documents
                   SET metadata = jsonb_set(metadata, '{status}', '"SUPERSEDED"')
                 WHERE metadata->>'source_type' = ?
                   AND metadata->>'source_id'   = ?
                   AND metadata->>'status'      = 'ACTIVE'
                """, source.getSourceType(), source.getSourceId());

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String hash = sha256(chunk);
            String vector = toPgVector(embeddings.get(i));
            String metadataJson;
            try {
                metadataJson = objectMapper.writeValueAsString(
                        buildMetadata(source, sourceVersion, i, hash));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize metadata", e);
            }

            jdbc.update("""
                    INSERT INTO public.kb_documents (content, embedding, metadata)
                    VALUES (?, ?::vector, ?::jsonb)
                    """,
                    chunk,
                    vector,
                    metadataJson);
        }
        log.info("Inserted {} kb_documents chunk(s) for {}:{} v{}",
                chunks.size(), source.getSourceType(), source.getSourceId(), sourceVersion);
    }

    /** Convenience for "delete this source from RAG entirely" (source row gone). */
    @Transactional
    public void supersedeAll(String sourceType, String sourceId) throws DataAccessException {
        jdbc.update("""
                UPDATE public.kb_documents
                   SET metadata = jsonb_set(metadata, '{status}', '"SUPERSEDED"')
                 WHERE metadata->>'source_type' = ?
                   AND metadata->>'source_id'   = ?
                   AND metadata->>'status'      = 'ACTIVE'
                """, sourceType, sourceId);
    }

    private Map<String, Object> buildMetadata(RagSource s, int version, int chunkIndex, String hash) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source_type", s.getSourceType());
        m.put("source_id", s.getSourceId());
        m.put("source_version", version);
        m.put("chunk_index", chunkIndex);
        m.put("content_hash", hash);
        m.put("title", s.getTitle());
        m.put("url", s.getUrl());
        m.put("status", "ACTIVE");
        return m;
    }

    private static String toPgVector(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 9 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
