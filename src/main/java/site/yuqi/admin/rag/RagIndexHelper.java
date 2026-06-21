package site.yuqi.admin.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import site.yuqi.admin.adapter.ContentAdapter;
import site.yuqi.admin.adapter.ContentAdapterRegistry;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.SourceType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Worker-ready helpers for the future RAG indexer. Admin APIs do NOT call
 * these — they exist here so a worker module can depend on a single jar.
 *
 * <p>The worker contract:
 * <ol>
 *     <li>Read pending RAG_INDEX jobs from {@link site.yuqi.admin.service.IndexingJobService}.</li>
 *     <li>For each job, call {@link #getContentForRag(SourceType, String)}.</li>
 *     <li>Chunk the text, embed, and write to {@code kb_documents} using
 *     {@link #buildKbMetadata(NormalizedContent, int, int, String)}.</li>
 *     <li>Call {@link #markOldKbDocumentsSuperseded(SourceType, String)} before
 *     writing new chunks (or wrap both in a tx, depending on isolation needs).</li>
 *     <li>Mark the job DONE.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class RagIndexHelper {

    private final ContentAdapterRegistry adapters;

    /** Returns the normalized content and the plain RAG text the worker should embed. */
    public Optional<NormalizedContent> getContentForRag(SourceType type, String sourceId) {
        ContentAdapter adapter = adapters.get(type);
        return adapter.get(sourceId);
    }

    public String ragText(NormalizedContent content) {
        return adapters.get(content.getSourceType()).toRagText(content);
    }

    /**
     * Build the metadata blob the worker will write to {@code kb_documents.metadata}.
     * {@code contentHash} should be the SHA-256 of the chunk text (or whole text for
     * single-chunk content).
     */
    public Map<String, Object> buildKbMetadata(NormalizedContent content,
                                               int version,
                                               int chunkIndex,
                                               String contentHash) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source_type", content.getSourceType().name());
        meta.put("source_id", content.getSourceId());
        meta.put("source_version", version);
        meta.put("chunk_index", chunkIndex);
        meta.put("content_hash", contentHash);
        meta.put("title", content.getTitle());
        meta.put("url", content.getUrl());
        meta.put("status", "ACTIVE");
        return meta;
    }

    /**
     * Marker stub for the worker. The actual UPDATE statement will run against
     * Supabase from the worker:
     * <pre>{@code
     *   update kb_documents
     *   set metadata = jsonb_set(metadata, '{status}', '"SUPERSEDED"')
     *   where metadata->>'source_type' = ?
     *     and metadata->>'source_id'   = ?
     *     and metadata->>'status'      = 'ACTIVE';
     * }</pre>
     *
     * <p>Implemented as a no-op stub in this module so {@code RagIndexHelper}
     * is self-documenting without pulling in the embeddings infrastructure.
     */
    public void markOldKbDocumentsSuperseded(SourceType sourceType, String sourceId) {
        // TODO(worker-impl): execute the SQL above via JdbcTemplate or Supabase REST.
    }
}
