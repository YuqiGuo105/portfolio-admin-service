package site.yuqi.admin.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.yuqi.admin.adapter.ContentAdapter;
import site.yuqi.admin.adapter.ContentAdapterRegistry;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobType;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.service.IndexingJobService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Polls {@code indexing_jobs} for pending {@code SEARCH_INDEX} rows and
 * pushes the document to OpenSearch via {@link SearchIndexClient}.
 *
 * <p>Runs in-process (this is a small service). For higher throughput,
 * extract to its own worker module later.
 *
 * <p>Document id in OpenSearch = {@code "<SOURCE_TYPE>:<sourceId>"} so
 * upserts replace the previous version automatically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexWorker {

    private final IndexingJobService indexingJobService;
    private final ContentAdapterRegistry adapters;
    private final SearchIndexClient searchClient;

    @Value("${portfolio.opensearch.worker.batch-size:20}")
    private int batchSize;

    @Value("${portfolio.opensearch.worker.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${portfolio.opensearch.worker.interval-ms:10000}",
               initialDelayString = "${portfolio.opensearch.worker.initial-delay-ms:15000}")
    public void drain() {
        if (!enabled) return;

        List<IndexingJob> batch = indexingJobService.listPendingIndexingJobs(JobType.SEARCH_INDEX, batchSize);
        if (batch.isEmpty()) return;

        log.info("SearchIndexWorker: processing {} SEARCH_INDEX job(s)", batch.size());
        for (IndexingJob job : batch) {
            process(job);
        }
    }

    private void process(IndexingJob job) {
        indexingJobService.markIndexingJobProcessing(job.getId());
        try {
            SourceType type = SourceType.valueOf(job.getSourceType());
            ContentAdapter adapter = adapters.get(type);
            Optional<NormalizedContent> maybe = adapter.get(job.getSourceIdText());

            if (maybe.isEmpty()) {
                // source row was deleted — remove from index
                searchClient.deleteDocument(documentId(type, job.getSourceIdText()));
                indexingJobService.markIndexingJobDone(job.getId());
                return;
            }

            NormalizedContent content = maybe.get();
            Map<String, Object> doc = adapter.toSearchDocument(content);
            searchClient.upsertDocument(documentId(type, content.getSourceId()), doc);

            indexingJobService.markIndexingJobDone(job.getId());
        } catch (Exception e) {
            log.warn("SEARCH_INDEX job {} failed: {}", job.getId(), e.getMessage());
            indexingJobService.markIndexingJobFailed(job.getId(), e.getMessage());
        }
    }

    private static String documentId(SourceType type, String sourceId) {
        return type.name() + ":" + sourceId;
    }
}
