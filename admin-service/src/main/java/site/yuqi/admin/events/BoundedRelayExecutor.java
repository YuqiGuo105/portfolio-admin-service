package site.yuqi.admin.events;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated bulkhead for blocking scale-to-zero wake calls and broker callbacks. */
@Component
public class BoundedRelayExecutor implements Executor {

    private final ThreadPoolExecutor delegate;

    public BoundedRelayExecutor(
            @Value("${portfolio.relay.executor.threads:4}") int threads,
            @Value("${portfolio.relay.executor.queue-capacity:50}") int queueCapacity) {
        AtomicInteger sequence = new AtomicInteger();
        int boundedThreads = Math.max(1, threads);
        delegate = new ThreadPoolExecutor(
                boundedThreads,
                boundedThreads,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                task -> {
                    Thread thread = new Thread(task, "content-relay-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        delegate.allowCoreThreadTimeOut(true);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    /** Returns false instead of pushing blocking wake work onto a Kafka callback thread. */
    public boolean tryExecute(Runnable command) {
        try {
            delegate.execute(command);
            return true;
        } catch (RejectedExecutionException saturated) {
            return false;
        }
    }

    @PreDestroy
    void close() {
        delegate.shutdown();
    }
}
