package site.yuqi.admin.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.events.BoundedRelayExecutor;
import site.yuqi.admin.events.IndexEventPublisher;
import site.yuqi.admin.operations.OperationContext;

/** Dispatch promptly on scale-to-zero instances; the durable job remains the recovery source. */
@Component
@RequiredArgsConstructor
@Slf4j
public class KnowledgeIndexDispatcher {
    private final IndexEventPublisher publisher;
    private final BoundedRelayExecutor executor;

    public void afterCommit(IndexingJob job) {
        OperationContext context = OperationContext.current();
        Runnable dispatch = () -> {
            if (!executor.tryExecute(() -> {
                OperationContext previous = OperationContext.current();
                try { OperationContext.set(context); publisher.publish(job); }
                catch (Exception failure) { log.warn("Knowledge indexing deferred job={}", job.getId()); }
                finally { OperationContext.set(previous); }
            })) log.warn("Knowledge indexing relay saturated job={}; durable job will recover", job.getId());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch.run(); }
            });
        } else dispatch.run();
    }
}
