package site.yuqi.admin.search;

import java.util.Map;

/**
 * Placeholder interface that a future search worker will implement to talk
 * to OpenSearch / Elasticsearch. Admin APIs do NOT inject or call this — only
 * background workers do.
 */
public interface SearchIndexClient {

    /**
     * Upsert a document keyed by {@code documentId}. The shape of {@code document}
     * is whatever {@link site.yuqi.admin.adapter.ContentAdapter#toSearchDocument} produced.
     */
    void upsertDocument(String documentId, Map<String, Object> document);

    void deleteDocument(String documentId);
}
