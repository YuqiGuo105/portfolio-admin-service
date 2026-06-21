package site.yuqi.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import site.yuqi.admin.adapter.ContentAdapter;
import site.yuqi.admin.adapter.ContentAdapterRegistry;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.ContentVersion;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobType;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.domain.Topic;
import site.yuqi.admin.events.IndexEventPublisher;
import site.yuqi.admin.events.NotificationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Orchestrator for admin content workflows. Wraps adapter calls with versioning,
 * outbox writes, indexing jobs and audit logs.
 *
 * <p>Notable invariants:
 * <ul>
 *     <li>The adapter is the only component that touches source tables.</li>
 *     <li>Admin APIs NEVER generate embeddings or call OpenSearch synchronously.</li>
 *     <li>Publish writes a {@code content_versions} row, an outbox event,
 *     two indexing jobs and an audit log atomically.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ContentService {

    private final ContentAdapterRegistry adapters;
    private final VersionService versionService;
    private final OutboxService outboxService;
    private final IndexingJobService indexingJobService;
    private final AuditLogService auditLogService;
    private final IndexEventPublisher indexEventPublisher;
    private final NotificationEventPublisher notificationEventPublisher;

    // ----- READS -----------------------------------------------------------

    public List<NormalizedContent> listAll(SourceType type, String keyword, String category,
                                           int limit, int offset) {
        if (type != null) {
            return adapters.get(type).list(keyword, category, limit, offset);
        }
        List<NormalizedContent> merged = new ArrayList<>();
        int perAdapter = Math.max(1, limit / adapters.all().size());
        for (ContentAdapter a : adapters.all()) {
            merged.addAll(a.list(keyword, category, perAdapter, 0));
        }
        return merged;
    }

    public NormalizedContent getOrThrow(SourceType type, String sourceId) {
        return adapters.get(type).get(sourceId)
                .orElseThrow(() -> new NoSuchElementException(type + " " + sourceId + " not found"));
    }

    // ----- WRITES ----------------------------------------------------------

    @Transactional
    public NormalizedContent create(SourceType type, Map<String, Object> input,
                                    boolean publish, String actor, String changeNote) {
        ContentAdapter adapter = adapters.get(type);
        NormalizedContent created = adapter.create(input);

        // Version 1 snapshot (does not depend on publish flag — every create is recorded).
        ContentVersion v1 = versionService.snapshot(created, 1, actor, "initial",
                adapter.toSnapshot(created));

        auditLogService.log(actor, AuditAction.CREATE, type, created.getSourceId(),
                v1.getVersion(), null, adapter.toSnapshot(created));

        if (publish) {
            publishInternal(adapter, created, actor, changeNote, /*reusedVersion*/ v1);
        }
        return created;
    }

    @Transactional
    public NormalizedContent update(SourceType type, String sourceId, Map<String, Object> input,
                                    boolean publish, String actor, String changeNote) {
        ContentAdapter adapter = adapters.get(type);
        NormalizedContent before = getOrThrow(type, sourceId);
        Map<String, Object> beforeSnap = adapter.toSnapshot(before);

        NormalizedContent after = adapter.update(sourceId, input);

        auditLogService.log(actor, AuditAction.UPDATE, type, sourceId,
                versionService.latestVersionFor(type.name(), sourceId),
                beforeSnap, adapter.toSnapshot(after));

        if (publish) {
            publishInternal(adapter, after, actor, changeNote, null);
        }
        return after;
    }

    @Transactional
    public PublishResult publish(SourceType type, String sourceId, String actor, String changeNote) {
        ContentAdapter adapter = adapters.get(type);
        NormalizedContent content = getOrThrow(type, sourceId);
        return publishInternal(adapter, content, actor, changeNote, null);
    }

    // ----- Reindex shortcuts ----------------------------------------------

    @Transactional
    public IndexingJob enqueueManualReindex(JobType jobType, SourceType type, String sourceId, String actor) {
        // We do not require the row to exist anymore in source tables — the job
        // payload only needs sourceType/sourceId/version. But fetching ensures
        // the version is current.
        int version = versionService.latestVersionFor(type.name(), sourceId);
        if (version == 0) {
            // Fallback: source has never been published. Use version 1 so the
            // worker can locate the latest source row.
            version = 1;
        }
        String key = IndexingJobService.manualKey(jobType, type, sourceId, version);
        IndexingJob job = indexingJobService.enqueue(jobType, type, sourceId, version, key);

        AuditAction action = jobType == JobType.RAG_INDEX ? AuditAction.REINDEX_RAG : AuditAction.REINDEX_SEARCH;
        auditLogService.log(actor, action, type, sourceId, version, null, Map.of("jobId", job.getId().toString()));
        return job;
    }

    // ----- internal --------------------------------------------------------

    private PublishResult publishInternal(ContentAdapter adapter, NormalizedContent content,
                                          String actor, String changeNote,
                                          ContentVersion existingVersionForCreate) {
        SourceType type = content.getSourceType();
        String sourceId = content.getSourceId();

        ContentVersion version;
        if (existingVersionForCreate != null) {
            version = existingVersionForCreate;
        } else {
            int next = versionService.nextVersionFor(type.name(), sourceId);
            version = versionService.snapshot(content, next, actor, changeNote, adapter.toSnapshot(content));
        }

        Topic topic = TopicMapping.topicFor(type);
        ContentEventOutbox outbox = outboxService.enqueuePublish(content, version.getVersion(), topic);

        IndexingJob ragJob = indexingJobService.enqueue(
                JobType.RAG_INDEX, type, sourceId, version.getVersion(),
                IndexingJobService.publishKey(JobType.RAG_INDEX, type, sourceId, version.getVersion()));

        IndexingJob searchJob = indexingJobService.enqueue(
                JobType.SEARCH_INDEX, type, sourceId, version.getVersion(),
                IndexingJobService.publishKey(JobType.SEARCH_INDEX, type, sourceId, version.getVersion()));

        auditLogService.log(actor, AuditAction.PUBLISH, type, sourceId, version.getVersion(),
                null, adapter.toSnapshot(content));

        // Fire all Kafka events only AFTER the DB transaction commits, so consumers
        // never see an event for a row that does not yet exist (or that rolled back).
        // If Kafka is down, indexing_jobs rows stay PENDING (polled later) and
        // the notification event is simply lost — tolerable because the outbox row
        // persists and a future outbox relay can re-send.
        publishAfterCommit(ragJob);
        publishAfterCommit(searchJob);
        notifyAfterCommit(content, version.getVersion(), topic);

        return PublishResult.builder()
                .version(version)
                .outboxEvent(outbox)
                .ragJob(ragJob)
                .searchJob(searchJob)
                .build();
    }

    /**
     * Publish a Kafka event for the indexing job, but only after the current
     * transaction commits successfully.
     */
    private void publishAfterCommit(IndexingJob job) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    indexEventPublisher.publish(job);
                }
            });
        } else {
            indexEventPublisher.publish(job);
        }
    }

    /**
     * Publish a notification event to the notification service topic after commit.
     * Uses a captured snapshot of {@code content} + version so the lambda does not
     * close over mutable state.
     */
    private void notifyAfterCommit(NormalizedContent content, int version, Topic topic) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notificationEventPublisher.publish(content, version, topic);
                }
            });
        } else {
            notificationEventPublisher.publish(content, version, topic);
        }
    }
}
