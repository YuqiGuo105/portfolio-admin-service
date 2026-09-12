package site.yuqi.admin.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.events.BoundedRelayExecutor;
import site.yuqi.admin.events.IndexEventPublisher;
import site.yuqi.admin.operations.OperationContext;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeIndexDispatcherTest {
    private final IndexEventPublisher publisher = mock(IndexEventPublisher.class);
    private final BoundedRelayExecutor executor = mock(BoundedRelayExecutor.class);
    private final KnowledgeIndexDispatcher dispatcher = new KnowledgeIndexDispatcher(publisher, executor);
    private final IndexingJob job = new IndexingJob();

    @AfterEach void clearTransaction() {
        OperationContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test void waitsUntilTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        dispatcher.afterCommit(job);
        verifyNoInteractions(executor, publisher);
        when(executor.tryExecute(any())).thenAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return true;
        });
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(publisher).publish(job);
    }

    @Test void rollbackNeverPublishes() {
        TransactionSynchronizationManager.initSynchronization();
        dispatcher.afterCommit(job);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(executor, publisher);
    }

    @Test void saturationDoesNotFailCommittedMutation() {
        when(executor.tryExecute(any())).thenReturn(false);
        assertThatCode(() -> dispatcher.afterCommit(job)).doesNotThrowAnyException();
        verifyNoInteractions(publisher);
    }

    @Test void transportFailureLeavesDurableJobForRecovery() {
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(job);
        when(executor.tryExecute(any())).thenAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return true;
        });
        assertThatCode(() -> dispatcher.afterCommit(job)).doesNotThrowAnyException();
        verify(publisher).publish(job);
    }

    @Test void propagatesTraceAcrossAsyncBoundary() {
        var request = new OperationContext("a".repeat(32), "knowledge-save", "admin-test");
        OperationContext.set(request);
        final Runnable[] task = new Runnable[1];
        when(executor.tryExecute(any())).thenAnswer(call -> { task[0] = call.getArgument(0); return true; });
        when(publisher.publish(job)).thenAnswer(call -> {
            assertThat(OperationContext.current()).isEqualTo(request);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        dispatcher.afterCommit(job);
        var worker = new OperationContext("b".repeat(32), "worker", "system");
        OperationContext.set(worker);
        task[0].run();
        assertThat(OperationContext.current()).isEqualTo(worker);
    }
}
