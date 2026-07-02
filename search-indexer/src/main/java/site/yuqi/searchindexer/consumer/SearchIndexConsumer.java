package site.yuqi.searchindexer.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import site.yuqi.searchindexer.events.ContentIndexEvent;
import site.yuqi.searchindexer.jobs.IndexingJobUpdater;
import site.yuqi.searchindexer.opensearch.OpenSearchIndexClient;
import site.yuqi.searchindexer.enrich.SearchTermsGenerator;
import site.yuqi.searchindexer.source.ContentFetcher;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes {@code content.search.index.v1} events. For each event:
 *   1. Mark indexing_jobs row as PROCESSING
 *   2. Fetch source row from Postgres
 *   3. Upsert (or delete) document in OpenSearch
 *   4. Mark indexing_jobs row as DONE (or FAILED on exception)
 *   5. Manually ack the Kafka offset.
 *
 * <p>Failures bump retry_count and leave the offset committed — admin-service
 * (or a future scheduled retry job) re-publishes after backoff.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexConsumer {

    private final IndexingJobUpdater jobs;
    private final ContentFetcher fetcher;
    private final OpenSearchIndexClient openSearch;
    private final SearchTermsGenerator searchTerms;

    @KafkaListener(
            topics = "${portfolio.kafka.topics.search-index}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onEvent(ConsumerRecord<String, ContentIndexEvent> record, Acknowledgment ack) {
        ContentIndexEvent evt = record.value();
        if (evt == null) {
            log.warn("Null event payload, skipping at offset={}", record.offset());
            ack.acknowledge();
            return;
        }

        UUID jobId;
        try {
            jobId = UUID.fromString(evt.getIndexingJobId());
        } catch (Exception e) {
            log.error("Invalid indexingJobId='{}' in event {} — skipping", evt.getIndexingJobId(), evt.getEventId());
            ack.acknowledge();
            return;
        }

        log.info("SEARCH_INDEX event={} {}:{} jobId={}",
                evt.getEventId(), evt.getSourceType(), evt.getSourceId(), jobId);

        try {
            jobs.markProcessing(jobId);

            Optional<Map<String, Object>> doc = fetcher.fetchSearchDocument(
                    evt.getSourceType(), evt.getSourceId());

            String documentId = evt.getSourceType() + ":" + evt.getSourceId();
            if (doc.isEmpty()) {
                openSearch.delete(documentId);
            } else {
                Map<String, Object> document = doc.get();
                // Document expansion (doc2query): one cheap chat call per document
                // populates `search_terms` for BM25 recall. Fail-open — an empty
                // result just means the document is indexed without expansion.
                String terms = searchTerms.generate(
                        str(document.get("title")),
                        str(document.get("summary")),
                        str(document.get("body")));
                if (terms != null && !terms.isBlank()) {
                    document.put("search_terms", terms);
                }
                openSearch.upsert(documentId, document);
            }

            jobs.markDone(jobId);
        } catch (Exception e) {
            log.error("SEARCH_INDEX job {} failed: {}", jobId, e.getMessage(), e);
            try {
                jobs.markFailed(jobId, e.getMessage());
            } catch (Exception inner) {
                log.error("Could not mark job {} as FAILED: {}", jobId, inner.getMessage());
            }
        } finally {
            ack.acknowledge();
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
