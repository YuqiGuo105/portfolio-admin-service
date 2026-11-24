package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.service.AuditLogService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaOriginalDeletionService {
    private static final Logger log = LoggerFactory.getLogger(MediaOriginalDeletionService.class);
    public record Check(UUID id, int metadataVersion, boolean deletable, List<String> reasons, String originalStatus) { }

    public static final class StorageDeletionException extends RuntimeException {
        StorageDeletionException() { super("Original deletion was not confirmed. Refresh the deletion check and retry."); }
    }

    private final MediaAssetStore assets;
    private final OriginalsStore originals;
    private final MediaUploadService uploads;
    private final AuditLogService audit;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public MediaOriginalDeletionService(MediaAssetStore assets, OriginalsStore originals, MediaUploadService uploads,
                                        AuditLogService audit, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.assets = assets;
        this.originals = originals;
        this.uploads = uploads;
        this.audit = audit;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactions);
        // Even an enclosing caller transaction cannot defer the durable intent until after deletion.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Check check(UUID id) {
        return tx.execute(transaction -> {
            var row = lock(id);
            List<String> reasons = reasons(row);
            return new Check(id, row.metadataVersion(), reasons.isEmpty(), reasons, row.originalStatus());
        });
    }

    public MediaUploadService.AssetView delete(UUID id, int expectedVersion, String actor) {
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion from media.get is required");
        tx.executeWithoutResult(transaction -> {
            var row = lock(id);
            if ("AVAILABLE".equals(row.originalStatus())) {
                requireVersion(row.metadataVersion() == expectedVersion);
                requireEligible(row);
                if (legacy(row)) {
                    String generation = legacyGeneration(row);
                    assets.bindLegacyOriginal(id, originals.storageEndpoint(), originals.deletionBucket(), generation);
                    audit.log(actor, AuditAction.UPDATE, "MEDIA", id.toString(), row.metadataVersion() + 1,
                            Map.of("originalStatus", "AVAILABLE"),
                            Map.of("operation", "ORIGINAL_BINDING", "receiptVerified", true, "sourceVersion", row.sourceVersion()));
                    row = lock(id);
                }
                assets.beginOriginalDeletion(row, actor);
                audit(actor, AuditAction.UPDATE, row, "DELETING");
            } else {
                var intent = intent(id);
                requireVersion(expectedVersion == row.metadataVersion() || expectedVersion == intent.expectedMetadataVersion());
                if (!"DELETED".equals(row.originalStatus())) {
                    requireEligible(row);
                    requireTarget(row, intent);
                    if (!"DELETING".equals(row.originalStatus())) {
                        assets.setOriginalStatus(id, "DELETING");
                        audit(actor, AuditAction.UPDATE, row, "DELETING");
                    }
                }
            }
        });

        // Keep the row locked across the bounded storage request. Concurrent duplicates wait and
        // then return the completed asset; crashes leave the committed intent for a manual retry.
        var result = tx.execute(transaction -> {
            var row = lock(id);
            if ("DELETED".equals(row.originalStatus())) return uploads.get(id);
            requireEligible(row);
            var intent = intent(id);
            requireTarget(row, intent);
            try {
                originals.deleteOriginal(id, intent.storageBucket(), intent.sourceKey(), intent.storageGeneration());
            } catch (IOException storageFailure) {
                if (storageFailure instanceof OriginalsStore.DeletionRequestException rejected) {
                    log.warn("Original deletion rejected: asset={} status={} providerCode={}",
                            id, rejected.status(), rejected.providerCode());
                } else {
                    log.warn("Original deletion unconfirmed: asset={} errorType={}", id, storageFailure.getClass().getSimpleName());
                }
                assets.setOriginalStatus(id, "DELETE_FAILED");
                audit(actor, AuditAction.UPDATE, row, "DELETE_FAILED");
                return null;
            }
            assets.setOriginalStatus(id, "DELETED");
            audit(actor, AuditAction.DELETE, row, "DELETED");
            return uploads.get(id);
        });
        if (result == null) throw new StorageDeletionException();
        return result;
    }

    private List<String> reasons(MediaAssetStore.AssetRow row) {
        if ("DELETED".equals(row.originalStatus())) return List.of("original_deleted");
        List<String> reasons = new ArrayList<>();
        if (!"VIDEO".equals(row.kind())) reasons.add("not_video");
        if (row.deletedAt() != null) reasons.add("asset_deleted");
        if (!"READY".equals(row.status())) reasons.add("not_ready");
        if (assets.hasActiveJobs(row.id())) reasons.add("active_jobs");
        if (!playable(row)) reasons.add("invalid_playable_manifest");
        try {
            String bucket = originals.deletionBucket();
            if (!legacy(row) && (!Objects.equals(row.originalStorageEndpoint(), originals.storageEndpoint())
                    || !Objects.equals(row.originalStorageBucket(), bucket)
                    || row.originalStorageGeneration() == null || !row.originalStorageGeneration().matches("[1-9][0-9]*")))
                throw new IllegalStateException("Upload-time storage binding is missing or changed");
            originals.validateDeletion(row.id(), bucket, row.sourceKey());
            String ownedKey = "originals/" + row.id() + "/" + row.uploadId() + "/" + row.originalFilename();
            if (row.sourceVersion() < 1 || !ownedKey.equals(row.sourceKey()) || !ownedKey.equals(row.uploadKey()))
                throw new IllegalStateException("Invalid source binding");
            if (legacy(row)) legacyGeneration(row);
            if (!"AVAILABLE".equals(row.originalStatus())) requireTarget(row, intent(row.id()));
        } catch (IllegalStateException | StorageDeletionException invalid) {
            reasons.add("original_storage_unverified");
        }
        return List.copyOf(reasons);
    }

    private static boolean legacy(MediaAssetStore.AssetRow row) {
        return row.originalStorageEndpoint() == null && row.originalStorageBucket() == null && row.originalStorageGeneration() == null;
    }

    private String legacyGeneration(MediaAssetStore.AssetRow row) {
        if (row.sourceBytes() == null || row.sourceBytes() != row.sizeBytes())
            throw new IllegalStateException("Original upload size receipt is missing or inconsistent");
        try {
            return originals.verifyLegacyOriginal(row.id(), row.sourceKey(), row.sourceBytes(), row.sourceEtag());
        } catch (IOException unavailable) {
            throw new StorageDeletionException();
        }
    }

    private boolean playable(MediaAssetStore.AssetRow row) {
        try {
            JsonNode manifest = mapper.readTree(row.manifestJson());
            MediaPresentationService.requirePlayback(manifest);
            return "VIDEO".equals(manifest.path("kind").asText()) && manifest.path("version").asInt() == 1
                    && manifest.path("basePath").asText().equals(
                    "media/" + row.id() + "/" + row.sourceVersion() + "/" + row.profileVersion());
        } catch (Exception invalid) {
            return false;
        }
    }

    private void requireEligible(MediaAssetStore.AssetRow row) {
        List<String> reasons = reasons(row);
        if (!reasons.isEmpty()) throw new IllegalStateException("Original cannot be deleted: " + String.join(", ", reasons));
    }

    private void requireTarget(MediaAssetStore.AssetRow row, MediaAssetStore.OriginalDeletion intent) {
        originals.validateDeletion(row.id(), intent.storageBucket(), intent.sourceKey());
        if (!Objects.equals(intent.storageEndpoint(), originals.storageEndpoint())
                || !Objects.equals(intent.storageEndpoint(), row.originalStorageEndpoint())
                || !Objects.equals(intent.storageBucket(), row.originalStorageBucket())
                || !Objects.equals(intent.storageGeneration(), row.originalStorageGeneration())
                || intent.sourceVersion() != row.sourceVersion() || !Objects.equals(intent.sourceKey(), row.sourceKey()))
            throw new IllegalStateException("Original source changed after deletion was requested");
    }

    private MediaAssetStore.OriginalDeletion intent(UUID id) {
        return assets.originalDeletion(id).orElseThrow(() -> new IllegalStateException("Deletion intent is missing"));
    }

    private MediaAssetStore.AssetRow lock(UUID id) {
        return assets.lock(id).orElseThrow(() -> new NoSuchElementException("Media not found"));
    }

    private static void requireVersion(boolean matches) {
        if (!matches) throw new IllegalStateException("Stale media metadata version; refresh the deletion check");
    }

    private void audit(String actor, AuditAction action, MediaAssetStore.AssetRow before, String next) {
        audit.log(actor, action, "MEDIA", before.id().toString(), before.metadataVersion() + 1,
                Map.of("originalStatus", before.originalStatus()),
                Map.of("operation", "ORIGINAL_DELETE", "originalStatus", next, "renditionsRetained", true));
    }
}
