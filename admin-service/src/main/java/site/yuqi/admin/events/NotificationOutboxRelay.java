package site.yuqi.admin.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.service.OutboxService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Retries notification events that were not acknowledged by Kafka. The
 * notification consumer de-duplicates on the outbox idempotency key, so a
 * replay after an ambiguous broker response is safe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "portfolio.outbox.worker.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NotificationOutboxRelay {

    private final OutboxService outboxService;
    private final NotificationEventPublisher publisher;

    @Scheduled(
            fixedDelayString = "${portfolio.outbox.worker.interval-ms:5000}",
            initialDelayString = "${portfolio.outbox.worker.initial-delay-ms:15000}")
    public void scheduledRelay() {
        drainOnce();
    }

    public int drainOnce() {
        List<ContentEventOutbox> events = outboxService.claimReadyOutboxEvents(
                25,
                60);
        if (!events.isEmpty()) {
            log.info("Relaying {} notification outbox event(s)", events.size());
        }
        CompletableFuture<?>[] deliveries = events.stream()
                .map(publisher::publish)
                .toArray(CompletableFuture[]::new);
        if (deliveries.length > 0) {
            try {
                CompletableFuture.allOf(deliveries).get(60, TimeUnit.SECONDS);
            } catch (Exception error) {
                log.warn("Notification relay batch did not fully complete: {}", error.getMessage());
            }
        }
        return events.size();
    }
}
