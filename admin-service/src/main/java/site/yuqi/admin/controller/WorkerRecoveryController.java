package site.yuqi.admin.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.events.IndexingJobRelay;
import site.yuqi.admin.events.NotificationOutboxRelay;

import java.util.Map;

/** Protected request-scoped recovery entry point for scale-to-zero workers. */
@RestController
@RequestMapping("/api/internal/workers")
public class WorkerRecoveryController {

    private final ObjectProvider<IndexingJobRelay> indexingRelay;
    private final ObjectProvider<NotificationOutboxRelay> notificationRelay;

    public WorkerRecoveryController(
            ObjectProvider<IndexingJobRelay> indexingRelay,
            ObjectProvider<NotificationOutboxRelay> notificationRelay) {
        this.indexingRelay = indexingRelay;
        this.notificationRelay = notificationRelay;
    }

    @PostMapping("/drain")
    public Map<String, Object> drain() {
        NotificationOutboxRelay notificationWorker = notificationRelay.getIfAvailable();
        IndexingJobRelay indexingWorker = indexingRelay.getIfAvailable();
        int notifications = notificationWorker == null ? 0 : notificationWorker.drainOnce();
        int indexing = indexingWorker == null ? 0 : indexingWorker.drainOnce();
        return Map.of("status", "accepted", "notificationEvents", notifications, "indexingJobs", indexing);
    }
}
