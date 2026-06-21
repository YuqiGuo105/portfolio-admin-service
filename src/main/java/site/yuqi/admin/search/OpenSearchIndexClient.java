package site.yuqi.admin.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

/**
 * Real OpenSearch implementation. Upserts content documents into the
 * {@code portfolio_content} index on the Aiven OpenSearch cluster.
 *
 * <p>Index schema (created automatically on first upsert if absent):
 * <pre>
 *   source_type  keyword
 *   source_id    keyword
 *   title        text (analyzed)
 *   summary      text (analyzed)
 *   content      text (analyzed)
 *   category     keyword
 *   tags         keyword (array)
 *   url          keyword
 *   image_url    keyword
 *   published_at date
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenSearchIndexClient implements SearchIndexClient {

    private final RestHighLevelClient client;

    @Value("${portfolio.opensearch.index:portfolio_content}")
    private String indexName;

    @Override
    public void upsertDocument(String documentId, Map<String, Object> document) {
        ensureIndex();
        try {
            IndexRequest req = new IndexRequest(indexName)
                    .id(documentId)
                    .source(document, XContentType.JSON);
            IndexResponse resp = client.index(req, RequestOptions.DEFAULT);
            log.debug("OpenSearch upsert [{}] {} → {}", indexName, documentId, resp.getResult());
        } catch (IOException e) {
            throw new RuntimeException("Failed to upsert document " + documentId + " to OpenSearch", e);
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        try {
            DeleteRequest req = new DeleteRequest(indexName, documentId);
            client.delete(req, RequestOptions.DEFAULT);
            log.debug("OpenSearch delete [{}] {}", indexName, documentId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete document " + documentId + " from OpenSearch", e);
        }
    }

    // ---- index bootstrap --------------------------------------------------

    private volatile boolean indexChecked = false;

    private void ensureIndex() {
        if (indexChecked) return;
        synchronized (this) {
            if (indexChecked) return;
            try {
                boolean exists = client.indices()
                        .exists(new GetIndexRequest(indexName), RequestOptions.DEFAULT);
                if (!exists) {
                    createIndex();
                }
                indexChecked = true;
            } catch (IOException e) {
                log.warn("Could not check/create OpenSearch index '{}': {}", indexName, e.getMessage());
            }
        }
    }

    private void createIndex() throws IOException {
        CreateIndexRequest req = new CreateIndexRequest(indexName);
        req.mapping(INDEX_MAPPINGS, XContentType.JSON);
        req.settings(INDEX_SETTINGS, XContentType.JSON);
        client.indices().create(req, RequestOptions.DEFAULT);
        log.info("Created OpenSearch index '{}'", indexName);
    }

    // ---- static mapping / settings JSON ----------------------------------

    private static final String INDEX_SETTINGS = """
            {
              "number_of_shards": 1,
              "number_of_replicas": 1
            }
            """;

    private static final String INDEX_MAPPINGS = """
            {
              "properties": {
                "source_type":   { "type": "keyword" },
                "source_id":     { "type": "keyword" },
                "title":         { "type": "text",    "analyzer": "english" },
                "summary":       { "type": "text",    "analyzer": "english" },
                "content":       { "type": "text",    "analyzer": "english" },
                "category":      { "type": "keyword" },
                "tags":          { "type": "keyword" },
                "url":           { "type": "keyword" },
                "image_url":     { "type": "keyword" },
                "published_at":  { "type": "date"    }
              }
            }
            """;
}
