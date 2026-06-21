package site.yuqi.searchindexer.opensearch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenSearchIndexClient {

    private final RestHighLevelClient client;

    @Value("${portfolio.opensearch.index}")
    private String indexName;

    public void upsert(String documentId, Map<String, Object> document) throws IOException {
        IndexRequest req = new IndexRequest(indexName)
                .id(documentId)
                .source(document, XContentType.JSON);
        client.index(req, RequestOptions.DEFAULT);
        log.debug("OpenSearch upsert [{}] {}", indexName, documentId);
    }

    public void delete(String documentId) throws IOException {
        client.delete(new DeleteRequest(indexName, documentId), RequestOptions.DEFAULT);
        log.debug("OpenSearch delete [{}] {}", indexName, documentId);
    }
}
