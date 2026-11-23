package site.yuqi.admin.mediapipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.service.AuditLogService;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaManagementService {
    public record Metadata(Integer expectedVersion, String title, String description) { }
    public record DeleteResult(UUID id, String status, int metadataVersion, boolean filesRetained, String notice) { }
    public record DeleteCheck(UUID id, int metadataVersion, boolean deletable, List<String> references,
                              boolean activeJobs, String status) { }

    private final MediaAssetStore assets;
    private final MediaUploadService uploads;
    private final AuditLogService audit;
    private final TransactionTemplate tx;

    public MediaManagementService(MediaAssetStore assets, MediaUploadService uploads, AuditLogService audit,
                                  PlatformTransactionManager transactions) {
        this.assets = assets;
        this.uploads = uploads;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    public MediaUploadService.AssetView update(UUID id, Metadata request, String actor) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0)
            throw new IllegalArgumentException("expectedVersion from media.get is required");
        String title = request.title() == null ? "" : request.title().trim();
        String description = request.description() == null ? "" : request.description().trim();
        if (title.isEmpty() || title.length() > 300 || description.length() > 2000)
            throw new IllegalArgumentException("Title must be 1-300 characters and description at most 2000");
        return tx.execute(transaction -> {
            var current = lock(id);
            requireVersion(current, request.expectedVersion());
            if (!assets.updateMetadata(id, request.expectedVersion(), title, description))
                throw new IllegalStateException("Media changed; read media.get before editing again");
            audit.log(actor, AuditAction.UPDATE, "MEDIA", id.toString(), current.metadataVersion() + 1,
                    snapshot(current), Map.of("title", title, "description", description));
            return uploads.get(id);
        });
    }

    public DeleteCheck checkDelete(UUID id) {
        var current = assets.find(id).orElseThrow(() -> new NoSuchElementException("Media not found"));
        var refs = assets.references(id);
        boolean jobs = assets.hasActiveJobs(id);
        boolean terminal = List.of("READY", "FAILED").contains(current.status());
        return new DeleteCheck(id, current.metadataVersion(), current.deletedAt() == null && terminal && !jobs && refs.isEmpty(),
                refs, jobs, current.deletedAt() == null ? current.status() : "DELETED");
    }

    public DeleteResult delete(UUID id, int expectedVersion, String actor) {
        return tx.execute(transaction -> {
            var current = lock(id);
            requireVersion(current, expectedVersion);
            var check = checkDelete(id);
            if (!check.deletable())
                throw new IllegalStateException("Media is in use or processing. Inspect deletion-check and remove references first");
            if (!assets.softDelete(id, expectedVersion)) throw new IllegalStateException("Media changed; read media.get again");
            audit.log(actor, AuditAction.DELETE, "MEDIA", id.toString(), current.metadataVersion() + 1,
                    snapshot(current), Map.of("status", "DELETED", "filesRetained", true));
            return new DeleteResult(id, "DELETED", current.metadataVersion() + 1, true,
                    "Removed from the library and public catalog. Stored files and cached playback URLs are not erased.");
        });
    }

    private MediaAssetStore.AssetRow lock(UUID id) {
        return assets.lock(id).orElseThrow(() -> new NoSuchElementException("Media not found"));
    }

    private static void requireVersion(MediaAssetStore.AssetRow row, int expected) {
        if (row.deletedAt() != null) throw new IllegalStateException("Media already deleted");
        if (expected < 0 || row.metadataVersion() != expected)
            throw new IllegalStateException("Stale media metadata version; read media.get again");
    }

    private static Map<String, Object> snapshot(MediaAssetStore.AssetRow row) {
        return Map.of("title", row.title() == null ? "" : row.title(), "description", row.description(),
                "status", row.status(), "metadataVersion", row.metadataVersion());
    }
}
