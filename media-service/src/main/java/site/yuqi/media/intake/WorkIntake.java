package site.yuqi.media.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import site.yuqi.media.db.JobFlow;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.model.Asset;
import site.yuqi.media.work.JobRunner;

import java.util.Optional;
import java.util.UUID;

/**
 * Step 3 → 4 of the pipeline: hear that an upload is complete and record the work.
 *
 * <p>All this does is insert one PLAN row. Probing and transcoding happen later, on worker threads,
 * so the Kafka poll loop never waits on FFmpeg. Once the row is committed the offset can be
 * committed too: if the process dies in between, Kafka replays the message and the unique key on
 * media_jobs turns the replay into a no-op.
 */
@Service
public class WorkIntake {

    public enum Outcome {
        /** Not a media event; the shared topic also carries indexing work. */
        IGNORED,
        ACCEPTED,
        /** The same work was recorded before. */
        DUPLICATE,
        /** The asset has since been given a newer source, or is no longer processing. */
        STALE
    }

    private static final Logger log = LoggerFactory.getLogger(WorkIntake.class);

    private final MediaRepository repository;
    private final JobFlow flow;
    private final JobRunner runner;

    public WorkIntake(MediaRepository repository, JobFlow flow, JobRunner runner) {
        this.repository = repository;
        this.flow = flow;
        this.runner = runner;
    }

    public Outcome accept(WorkEvent event) {
        if (event == null || !event.isMediaWork()) return Outcome.IGNORED;

        UUID assetId = parseAssetId(event);
        if (event.sourceVersion() == null || event.sourceVersion() < 1) {
            throw new PoisonEventException("media event " + event.eventId() + " has no source version");
        }
        Optional<Asset> found = repository.findAsset(assetId);
        if (found.isEmpty()) {
            throw new PoisonEventException("media event " + event.eventId() + " names unknown asset " + assetId);
        }
        Asset asset = found.get();
        if (asset.sourceVersion() != event.sourceVersion() || !Asset.PROCESSING.equals(asset.status())) {
            log.info("Ignoring stale media event {} for asset {} (event v{}, asset v{} {})",
                    event.eventId(), assetId, event.sourceVersion(), asset.sourceVersion(), asset.status());
            return Outcome.STALE;
        }
        if (event.sourceKey() != null && !event.sourceKey().equals(asset.sourceKey())) {
            throw new PoisonEventException("media event " + event.eventId() + " points at " + event.sourceKey()
                    + " but asset " + assetId + " v" + asset.sourceVersion() + " is bound to " + asset.sourceKey());
        }
        String profile = event.profileVersion() != null ? event.profileVersion() : asset.profileVersion();
        if (profile == null || profile.isBlank()) {
            throw new PoisonEventException("media event " + event.eventId() + " has no encoding profile");
        }

        boolean created = flow.acceptWork(assetId, asset.sourceVersion(), profile);
        if (created) {
            log.info("Accepted media work asset={} v{} profile={} event={}",
                    assetId, asset.sourceVersion(), profile, event.eventId());
            runner.wake();
        }
        return created ? Outcome.ACCEPTED : Outcome.DUPLICATE;
    }

    private static UUID parseAssetId(WorkEvent event) {
        try {
            return UUID.fromString(event.sourceId());
        } catch (RuntimeException malformed) {
            throw new PoisonEventException("media event " + event.eventId() + " has a malformed asset id");
        }
    }

    /** The message itself is wrong and will never succeed; it goes to quarantine, not to retry. */
    public static final class PoisonEventException extends RuntimeException {
        public PoisonEventException(String message) {
            super(message);
        }
    }
}
