package site.yuqi.admin.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.service.IndexingJobService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "portfolio.indexing-relay.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class IndexingJobRelay {

    private final IndexingJobService jobs;
    private final IndexEventPublisher publisher;

    @Scheduled(
            fixedDelayString = "${portfolio.indexing-relay.interval-ms:10000}",
            initialDelayString = "${portfolio.indexing-relay.initial-delay-ms:15000}")
    public void scheduledRelay() {
        drainOnce();
    }

    public int drainOnce() {
        List<IndexingJob> ready = jobs.claimReadyIndexingJobs(20, 300);
        CompletableFuture<?>[] deliveries = ready.stream()
                .map(publisher::publish)
                .toArray(CompletableFuture[]::new);
        if (deliveries.length > 0) {
            try {
                CompletableFuture.allOf(deliveries).get(240, TimeUnit.SECONDS);
            } catch (Exception error) {
                log.warn("Indexing relay batch did not fully complete: {}", error.getMessage());
            }
        }
        if (!ready.isEmpty()) log.info("Relayed {} indexing job(s)", ready.size());
        return ready.size();
    }
}
