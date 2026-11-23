package site.yuqi.media.db;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.yuqi.media.model.Asset;
import site.yuqi.media.model.Job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The state changes that have to commit together.
 *
 * <p>Every method takes the asset's row lock first and touches job rows second. That one order
 * means two jobs of the same asset can never finish "at the same time" and both miss that they
 * were the last one, and it rules out lock cycles between workers.
 */
@Service
public class JobFlow {

    public enum Completion {
        /** The result was accepted. */
        ACCEPTED,
        /** The lease had moved on: another attempt owns the job, this result is discarded. */
        LEASE_LOST
    }

    public enum Failure { RETRY, DEAD, LEASE_LOST }

    /** A job the PLAN step wants created. */
    public record NewJob(String jobType, String rendition, String specJson) {
    }

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final MediaRepository repository;
    private final TransactionTemplate tx;

    public JobFlow(MediaRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Intake: records that this source version needs processing. Called once per Kafka message,
     * and safe to call again for the same message.
     *
     * @return true when the PLAN job was created by this call
     */
    public boolean acceptWork(UUID assetId, int sourceVersion, String profileVersion) {
        return repository.insertJob(assetId, sourceVersion, profileVersion, Job.PLAN, "plan", "{}",
                DEFAULT_MAX_ATTEMPTS);
    }

    /** PLAN finished: stores what the probe found and creates one job per output, all or nothing. */
    public Completion completePlan(Job plan, String owner, String probeJson, List<NewJob> jobs, String outputJson) {
        return tx.execute(status -> {
            Optional<Asset> asset = repository.lockAsset(plan.assetId());
            if (!repository.complete(plan, owner, outputJson)) return Completion.LEASE_LOST;
            if (asset.isEmpty() || !isCurrent(asset.get(), plan)) return Completion.ACCEPTED;
            repository.saveProbe(plan.assetId(), plan.sourceVersion(), probeJson);
            for (NewJob job : jobs) {
                repository.insertJob(plan.assetId(), plan.sourceVersion(), plan.profileVersion(),
                        job.jobType(), job.rendition(), job.specJson(), DEFAULT_MAX_ATTEMPTS);
            }
            return Completion.ACCEPTED;
        });
    }

    /** An output job finished. When it was the last one, the PUBLISH job is queued in the same commit. */
    public Completion completeOutput(Job job, String owner, String outputJson) {
        return tx.execute(status -> {
            repository.lockAsset(job.assetId());
            if (!repository.complete(job, owner, outputJson)) return Completion.LEASE_LOST;
            queuePublishIfReady(job.assetId(), job.sourceVersion(), job.profileVersion());
            return Completion.ACCEPTED;
        });
    }

    /**
     * PUBLISH finished its checks: move the publish pointer. Returns false when the asset has moved
     * on to another source version, in which case nothing visitors see has changed.
     */
    public boolean publish(Job publish, String owner, String manifestJson) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            repository.lockAsset(publish.assetId());
            boolean published = repository.publish(
                    publish.assetId(), publish.sourceVersion(), publish.profileVersion(), manifestJson);
            String output = published ? "{\"published\":true}" : "{\"published\":false,\"reason\":\"superseded\"}";
            if (!repository.complete(publish, owner, output)) {
                // Lost the lease while publishing: undo, the attempt that owns the job will publish.
                status.setRollbackOnly();
                return false;
            }
            return published;
        }));
    }

    /**
     * A job failed. Transient errors go back to PENDING with a delay; permanent ones, and the last
     * allowed attempt, end the job and fail the asset.
     */
    public Failure fail(Job job, String owner, String error, boolean permanent, long backoffSeconds) {
        return tx.execute(status -> {
            repository.lockAsset(job.assetId());
            Optional<String> next = repository.fail(job, owner, error, permanent, backoffSeconds);
            if (next.isEmpty()) return Failure.LEASE_LOST;
            if (!Job.DEAD.equals(next.get())) return Failure.RETRY;
            failAsset(job.assetId(), job.sourceVersion(), job.profileVersion(),
                    permanent ? "unsupported_input" : "processing_failed", error);
            return Failure.DEAD;
        });
    }

    /** Ends an asset whose job is DEAD: the remaining queued jobs are cancelled, the reason is kept. */
    public void failAsset(UUID assetId, int sourceVersion, String profileVersion, String code, String message) {
        tx.executeWithoutResult(status -> {
            repository.lockAsset(assetId);
            repository.cancelPending(assetId, sourceVersion, profileVersion, "cancelled: another job of this asset failed");
            repository.failAsset(assetId, sourceVersion, code, message);
        });
    }

    /** Queues PUBLISH when every output job has succeeded. Safe to call at any time, from anywhere. */
    public boolean queuePublishIfReady(UUID assetId, int sourceVersion, String profileVersion) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            repository.lockAsset(assetId);
            if (!repository.allOutputsSucceeded(assetId, sourceVersion, profileVersion)) return false;
            return repository.insertJob(assetId, sourceVersion, profileVersion, Job.PUBLISH, "publish", "{}",
                    DEFAULT_MAX_ATTEMPTS);
        }));
    }

    private static boolean isCurrent(Asset asset, Job job) {
        return Asset.PROCESSING.equals(asset.status()) && asset.sourceVersion() == job.sourceVersion();
    }
}
