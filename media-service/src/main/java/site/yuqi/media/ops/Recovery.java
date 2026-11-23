package site.yuqi.media.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.db.JobFlow;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.model.Asset;
import site.yuqi.media.model.Job;
import site.yuqi.media.work.JobRunner;

import java.util.List;

/**
 * The periodic recovery task: everything here repairs a state that the normal path would have
 * handled if no process had died and no message had been lost. Each sweep is safe to run from
 * every instance at the same time, because each repair is an idempotent insert or a guarded update.
 */
@Component
public class Recovery {

    private static final Logger log = LoggerFactory.getLogger(Recovery.class);

    private final MediaRepository repository;
    private final JobFlow flow;
    private final JobRunner runner;
    private final MediaProperties properties;

    public Recovery(MediaRepository repository, JobFlow flow, JobRunner runner, MediaProperties properties) {
        this.repository = repository;
        this.flow = flow;
        this.runner = runner;
        this.properties = properties;
    }

    /**
     * A worker that stops renewing its lease has crashed, hung or lost the database. Its job goes
     * back in the queue as a new attempt; the last allowed attempt ends the job and the asset.
     */
    @Scheduled(fixedDelayString = "${portfolio.media-pipeline.recovery.lease-sweep-ms:15000}",
            initialDelayString = "${portfolio.media-pipeline.recovery.initial-delay-ms:10000}")
    public void reapExpiredLeases() {
        List<Job> reaped = repository.reapExpiredLeases();
        for (Job job : reaped) {
            log.warn("Lease expired: job {} {} {} attempt {} -> {}", job.id(), job.jobType(), job.rendition(),
                    job.attempt(), job.status());
            if (Job.DEAD.equals(job.status())) {
                flow.failAsset(job.assetId(), job.sourceVersion(), job.profileVersion(), "processing_failed",
                        job.rendition() + " did not finish in " + job.maxAttempts() + " attempts");
            }
        }
        if (!reaped.isEmpty()) runner.wake();
    }

    /** Repairs the gaps between steps: a lost message, a crash after the last output, a crash after a job died. */
    @Scheduled(fixedDelayString = "${portfolio.media-pipeline.recovery.reconcile-ms:30000}",
            initialDelayString = "${portfolio.media-pipeline.recovery.initial-delay-ms:10000}")
    public void reconcile() {
        int repaired = 0;
        for (Asset asset : repository.findUnplanned(properties.recovery().unplannedAfterSeconds())) {
            if (flow.acceptWork(asset.id(), asset.sourceVersion(), asset.profileVersion())) {
                log.warn("Asset {} v{} had no PLAN job; the upload event was lost or expired. Queued now.",
                        asset.id(), asset.sourceVersion());
                repaired++;
            }
        }
        for (Asset asset : repository.findPublishable()) {
            if (flow.queuePublishIfReady(asset.id(), asset.sourceVersion(), asset.profileVersion())) {
                log.warn("Asset {} v{} had every output but no PUBLISH job. Queued now.",
                        asset.id(), asset.sourceVersion());
                repaired++;
            }
        }
        for (Asset asset : repository.findProcessingWithDeadJob()) {
            String reason = repository.firstError(asset.id(), asset.sourceVersion(), asset.profileVersion())
                    .orElse("a processing job failed");
            flow.failAsset(asset.id(), asset.sourceVersion(), asset.profileVersion(), "processing_failed", reason);
            repaired++;
        }
        if (repaired > 0) runner.wake();
    }
}
