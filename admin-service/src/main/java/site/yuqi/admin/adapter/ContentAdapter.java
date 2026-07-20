package site.yuqi.admin.adapter;

import site.yuqi.admin.domain.SourceType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Strategy interface that hides per-table schema differences. The admin
 * services orchestrate; adapters do the table I/O and normalization.
 */
public interface ContentAdapter {

    SourceType sourceType();

    /**
     * List rows from the underlying table, normalized.
     *
     * @param keyword optional case-insensitive substring match across title/body
     * @param category optional exact-match category filter (may be ignored for EXPERIENCE)
     * @param limit page size, applied after defaulting in the adapter
     * @param offset starting offset
     */
    List<NormalizedContent> list(String keyword, String category, int limit, int offset);

    Optional<NormalizedContent> get(String sourceId);

    NormalizedContent create(Map<String, Object> input);

    NormalizedContent update(String sourceId, Map<String, Object> input);

    /**
     * Applies source-table publication metadata immediately before the publish
     * transaction creates outbox and indexing records. Adapters whose source
     * schema has no publication marker may keep the default no-op behavior.
     */
    default NormalizedContent markPublished(String sourceId) {
        return get(sourceId)
                .orElseThrow(() -> new IllegalArgumentException(sourceType() + " not found: " + sourceId));
    }

    /** Snapshot serialization for content_versions and audit logs. */
    Map<String, Object> toSnapshot(NormalizedContent content);

    /** Plain text for the RAG worker to embed (worker handles chunking + embeddings). */
    String toRagText(NormalizedContent content);

    /** Document body the SEARCH worker will upsert (e.g. into OpenSearch). */
    Map<String, Object> toSearchDocument(NormalizedContent content);

    /** Site-relative URL where the published item is viewed. */
    String toUrl(NormalizedContent content);
}
