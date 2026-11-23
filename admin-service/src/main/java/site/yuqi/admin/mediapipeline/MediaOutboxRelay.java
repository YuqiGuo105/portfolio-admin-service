package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.yuqi.admin.mediapipeline.MediaAssetStore.OutboxRow;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The independent delivery program of the transactional outbox: it takes events that are owed,
 * sends them, and marks them sent. A failed send is tried again later, with growing pauses, for as
 * long as it takes. An event may therefore arrive more than once; the Media Service is built for that.
 *
 * <p>media_event_outbox is a separate table from content_event_outbox because that relay claims
 * every row it sees and would try to send these as notifications.
 */
@Component
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(MediaOutboxRelay.class);
    private static final int BATCH = 20;
    private static final long START_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int LEASE_SECONDS = 120;

    private final MediaAssetStore store;
    private final MediaEventTransport transport;
    private final ObjectMapper mapper;
    private final int maxAttempts;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final ExecutorService background = new ThreadPoolExecutor(1, 3, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50), task -> {
        Thread thread = new Thread(task, "media-relay");
        thread.setDaemon(true);
        return thread;
    });

    public MediaOutboxRelay(MediaAssetStore store, MediaEventTransport transport,
                            ObjectMapper mapper, MediaPipelineProperties properties) {
        this.store = store;
        this.transport = transport;
        this.mapper = mapper;
        this.maxAttempts = properties.events().maxAttempts();
    }

    @Scheduled(fixedDelayString = "${portfolio.media-pipeline.events.relay-interval-ms:5000}",
            initialDelayString = "${portfolio.media-pipeline.events.relay-initial-delay-ms:15000}")
    public void scheduledRelay() {
        drainOnce();
    }

    /** Asks for a delivery attempt now instead of at the next tick. Never blocks the caller. */
    public void kick() {
        submit(this::drainOnce);
    }

    /** Bounded pass: leave unclaimed work for the next tick instead of holding a request indefinitely. */
    public int drainOnce() {
        if (!draining.compareAndSet(false, true)) return 0;
        int sent = 0;
        long started = System.nanoTime();
        try {
            for (int attempted = 0; attempted < BATCH && !Thread.currentThread().isInterrupted()
                    && System.nanoTime()-started < START_BUDGET_NANOS; attempted++) {
                // Claim only what this pass can actually send; do not strand a batch under leases.
                List<OutboxRow> batch = store.claimOutbox(1, LEASE_SECONDS);
                if (batch.isEmpty()) break;
                if (deliver(batch.getFirst())) sent++;
            }
        } catch (RuntimeException databaseTrouble) {
            log.warn("Media outbox relay could not read the outbox: {}",
                    String.valueOf(databaseTrouble.getMessage()).lines().findFirst().orElse(""));
        } finally {
            draining.set(false);
        }
        return sent;
    }

    private boolean deliver(OutboxRow row) {
        try {
            transport.send(mapper.readValue(row.payloadJson(), MediaWorkEvent.class));
            store.markOutboxSent(row.id());
            log.info("Media event {} delivered for asset {} v{} (attempt {})", row.eventId(), row.assetId(),
                    row.sourceVersion(), row.attempts());
            // wake_status remains pending in this durable row until the separate dispatcher finishes.
            return true;
        } catch (Exception failed) {
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            long retryIn = Math.min(600, 5L << Math.min(7, row.attempts() - 1));
            store.markOutboxFailed(row.id(), failed.toString(), retryIn, maxAttempts);
            log.warn("Media event {} not delivered (attempt {}), next try in {} s: {}", row.eventId(),
                    row.attempts(), retryIn, failed.toString());
            return false;
        }
    }

    private void submit(Runnable task) {
        try {
            background.execute(task);
        } catch (RejectedExecutionException saturated) {
            // The row remains due; a scheduled tick or external recovery request can retry it.
            log.debug("Media relay background queue is full");
        }
    }

    @PreDestroy
    void close() {
        background.shutdown();
        try {
            if (!background.awaitTermination(35, TimeUnit.SECONDS)) {
                background.shutdownNow();
                background.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            background.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
