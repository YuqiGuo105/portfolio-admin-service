package site.yuqi.admin.mediapipeline;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable wake delivery is independent of Kafka publication; the HTTP caller waits for the batch. */
@Component
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaWakeDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MediaWakeDispatcher.class);
    private final MediaWakeStore store;
    private final MediaServiceWakeClient wake;
    private final MediaPresentationJobs presentations;
    private final int maxAttempts;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task,"media-wake"); thread.setDaemon(true); return thread;
    });
    private CompletableFuture<Void> inFlight = CompletableFuture.completedFuture(null);

    public MediaWakeDispatcher(MediaWakeStore store, MediaServiceWakeClient wake, MediaPipelineProperties config,
                               MediaPresentationJobs presentations) {
        this.store=store; this.wake=wake; this.maxAttempts=Math.max(1,config.events().maxAttempts());
        this.presentations=presentations;
    }

    private synchronized CompletableFuture<Void> startBatch() {
        if (!inFlight.isDone()) return inFlight;
        if (!wake.isConfigured()) { presentations.reconcile(); return CompletableFuture.completedFuture(null); }
        var deliveries=store.claim(2, (int)(wake.waitMillis()/1000)+60, maxAttempts);
        inFlight=CompletableFuture.allOf(deliveries.stream().map(delivery -> CompletableFuture.runAsync(() -> {
            boolean done=wake.wakeAndAwait(delivery.assetId());
            store.finish(delivery,done,maxAttempts);
            if (!done && delivery.attempt() >= maxAttempts) {
                log.error("Media wake exhausted retries for asset {}; inspect wake DLQ",delivery.assetId());
            }
        },workers)).toArray(CompletableFuture[]::new)).thenRun(presentations::reconcile);
        return inFlight;
    }

    @Scheduled(fixedDelayString="${portfolio.media-pipeline.events.relay-interval-ms:5000}")
    public void tick() {
        try { startBatch().exceptionally(error -> { log.warn("Media wake batch failed; leases will recover",error); return null; }); }
        catch (RuntimeException failure) { log.warn("Media wake could not claim deliveries",failure); }
    }

    public Map<String,Integer> drainAndAwait() {
        startBatch().join();
        return store.counts();
    }

    @PreDestroy
    public void close() { workers.shutdownNow(); }
}
