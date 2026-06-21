package site.yuqi.ragindexer.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import site.yuqi.ragindexer.events.ContentIndexEvent;
import site.yuqi.ragindexer.jobs.IndexingJobUpdater;
import site.yuqi.ragindexer.openai.OpenAiEmbeddingClient;
import site.yuqi.ragindexer.rag.ContentChunker;
import site.yuqi.ragindexer.rag.KbDocumentWriter;
import site.yuqi.ragindexer.source.ContentFetcher;
import site.yuqi.ragindexer.source.RagSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes {@code content.rag.index.v1} events. For each event:
 *   1. Mark indexing_jobs row PROCESSING
 *   2. Fetch source row (or supersede all kb_documents if it was deleted)
 *   3. Chunk content text
 *   4. Embed each chunk via OpenAI
 *   5. Mark previously-ACTIVE chunks SUPERSEDED + insert new ACTIVE chunks (one tx)
 *   6. Mark indexing_jobs row DONE (or FAILED on exception)
 *   7. Manually ack the Kafka offset.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagIndexConsumer {

    private final IndexingJobUpdater jobs;
    private final ContentFetcher fetcher;
    private final ContentChunker chunker;
    private final OpenAiEmbeddingClient embedder;
    private final KbDocumentWriter writer;

    @KafkaListener(
            topics = "${portfolio.kafka.topics.rag-index}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onEvent(ConsumerRecord<String, ContentIndexEvent> record, Acknowledgment ack) {
        ContentIndexEvent evt = record.value();
        if (evt == null) {
            log.warn("Null event payload at offset={}", record.offset());
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

        log.info("RAG_INDEX event={} {}:{} v{} jobId={}",
                evt.getEventId(), evt.getSourceType(), evt.getSourceId(),
                evt.getSourceVersion(), jobId);

        try {
            jobs.markProcessing(jobId);

            Optional<RagSource> sourceOpt = fetcher.fetch(evt.getSourceType(), evt.getSourceId());
            if (sourceOpt.isEmpty()) {
                // Source row deleted — supersede everything we previously indexed for it.
                writer.supersedeAll(evt.getSourceType(), evt.getSourceId());
                jobs.markDone(jobId);
                return;
            }

            RagSource source = sourceOpt.get();
            String fullText = buildRagText(source);
            List<String> chunks = chunker.chunk(fullText);
            if (chunks.isEmpty()) {
                log.info("No content to embed for {}:{} — superseding old chunks only",
                        source.getSourceType(), source.getSourceId());
                writer.supersedeAll(source.getSourceType(), source.getSourceId());
                jobs.markDone(jobId);
                return;
            }

            List<float[]> embeddings = new ArrayList<>(chunks.size());
            for (String c : chunks) {
                embeddings.add(embedder.embed(c));
            }

            writer.supersedeAndInsert(source, evt.getSourceVersion(), chunks, embeddings);
            jobs.markDone(jobId);
        } catch (Exception e) {
            log.error("RAG_INDEX job {} failed: {}", jobId, e.getMessage(), e);
            try {
                jobs.markFailed(jobId, e.getMessage());
            } catch (Exception inner) {
                log.error("Could not mark job {} as FAILED: {}", jobId, inner.getMessage());
            }
        } finally {
            ack.acknowledge();
        }
    }

    /** Build the embedded text by stitching title + summary + content. */
    private String buildRagText(RagSource s) {
        StringBuilder sb = new StringBuilder();
        if (s.getTitle()   != null && !s.getTitle().isBlank())   sb.append(s.getTitle()).append("\n\n");
        if (s.getSummary() != null && !s.getSummary().isBlank()) sb.append(s.getSummary()).append("\n\n");
        if (s.getContent() != null && !s.getContent().isBlank()) sb.append(s.getContent());
        return sb.toString().strip();
    }
}
