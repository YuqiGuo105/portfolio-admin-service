package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.yuqi.admin.mediapipeline.MediaAssetStore.AssetRow;
import site.yuqi.admin.operations.OperationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Steps 1 to 3 of the pipeline: start an upload, confirm the original is completely stored, and
 * announce it.
 *
 * <p>The announcement is not sent from here. Confirming the upload writes the new source version,
 * the PROCESSING status and one outbox row in a single transaction; a separate relay delivers the
 * row to Kafka and keeps trying until it succeeds. The database therefore always knows whether a
 * message is still owed, and a crash between "saved" and "sent" loses nothing.
 */
@Service
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaUploadService {

    public record StartUpload(String kind, String filename, String contentType, long sizeBytes, String title) {
    }

    public record PartUrl(int partNumber, String url) {
    }

    /** What the browser needs to upload: where each part goes, and which parts are already there. */
    public record UploadSession(UUID assetId, String uploadId, long partSizeBytes, int partCount,
                                List<Integer> uploadedParts, List<PartUrl> parts, Instant expiresAt,
                                String filename, String contentType, long sizeBytes) {
    }

    public record JobView(String type, String rendition, String status, int attempt, String error,
                          int maxAttempts, Instant startedAt, Instant finishedAt) {
    }

    public record UsageView(String label, String href, int displayWidth) { }

    public record AssetView(UUID id, String kind, String status, String title, String filename, String contentType,
                            long sizeBytes, int sourceVersion, String failureCode, String failureMessage,
                            JsonNode manifest, List<JobView> jobs, Instant createdAt, Instant updatedAt,
                            JsonNode probe, List<UsageView> usages, String description, int metadataVersion,
                            Instant deletedAt, String originalStatus, Instant originalDeletedAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);

    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm", "video/x-matroska");
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/avif",
            "image/heic", "image/heif");
    private static final long MIN_PART_BYTES = 5L * 1024 * 1024;
    private static final int MAX_PARTS = 10_000;

    private final MediaAssetStore assets;
    private final OriginalsStore originals;
    private final MediaPipelineProperties properties;
    private final MediaOutboxRelay relay;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public MediaUploadService(MediaAssetStore assets, OriginalsStore originals, MediaPipelineProperties properties,
                              MediaOutboxRelay relay, PlatformTransactionManager transactionManager,
                              ObjectMapper mapper) {
        this.assets = assets;
        this.originals = originals;
        this.properties = properties;
        this.relay = relay;
        this.tx = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
    }

    /** Step 1: the browser asks to upload. Creates the asset and a multipart upload, returns one URL per part. */
    public UploadSession start(StartUpload request, String actor) {
        String contentType = request.contentType() == null ? "" : request.contentType().trim().toLowerCase(Locale.ROOT);
        String kind = VIDEO_TYPES.contains(contentType) ? "VIDEO" : IMAGE_TYPES.contains(contentType) ? "IMAGE" : null;
        if (kind == null) {
            throw new IllegalArgumentException("Content type '" + contentType + "' is not accepted. Videos: MP4, MOV, "
                    + "WebM, MKV. Images: JPEG, PNG, WebP, AVIF, HEIC.");
        }
        if (request.kind() != null && !request.kind().isBlank() && !kind.equalsIgnoreCase(request.kind())) {
            throw new IllegalArgumentException("kind " + request.kind() + " does not match content type " + contentType);
        }
        MediaPipelineProperties.Upload limits = properties.upload();
        long max = "VIDEO".equals(kind) ? limits.maxVideoBytes() : limits.maxImageBytes();
        if (request.sizeBytes() <= 0 || request.sizeBytes() > max) {
            throw new IllegalArgumentException("sizeBytes must be between 1 and " + max + " for a " + kind.toLowerCase(Locale.ROOT));
        }
        String filename = safeName(request.filename());
        String title = blankToNull(request.title());
        if (title != null && title.length() > 300) {
            throw new IllegalArgumentException("Title must be at most 300 characters");
        }

        long partSize = Math.max(MIN_PART_BYTES, limits.partSizeBytes());
        while ((request.sizeBytes() + partSize - 1) / partSize > MAX_PARTS) partSize *= 2;
        int partCount = (int) ((request.sizeBytes() + partSize - 1) / partSize);

        UUID assetId = UUID.randomUUID();
        String uploadId = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String key = "originals/" + assetId + "/" + uploadId + "/" + filename;
        String storageUploadId;
        try {
            storageUploadId = originals.createMultipartUpload(key, contentType);
        } catch (IOException storeTrouble) {
            throw new UncheckedIOException("Could not start the upload in object storage", storeTrouble);
        }
        try {
            assets.insertUploading(assetId, kind, title, filename, contentType, request.sizeBytes(),
                    uploadId, storageUploadId, key, partSize, partCount, limits.sessionTtlSeconds(), actor,
                    originals.storageEndpoint(), originals.storageBucket());
        } catch (RuntimeException failed) {
            try {
                originals.abortMultipartUpload(key, storageUploadId);
            } catch (IOException cleanupFailed) {
                failed.addSuppressed(cleanupFailed);
                log.warn("Could not clean up unbound multipart upload for asset {}", assetId);
            }
            throw failed;
        }
        log.info("Media upload started asset={} kind={} bytes={} parts={}", assetId, kind, request.sizeBytes(), partCount);
        return session(assets.find(assetId).orElseThrow(), List.of());
    }

    /** For resuming: which parts the store already has, and fresh URLs for all of them. */
    public UploadSession resume(UUID assetId, String uploadId) {
        AssetRow asset = requireOpenUpload(assetId, uploadId);
        try {
            List<Integer> uploaded = originals.listParts(asset.uploadKey(), asset.storageUploadId()).stream()
                    .map(OriginalsStore.Part::number).sorted().toList();
            return session(asset, uploaded);
        } catch (IOException storeTrouble) {
            throw new UncheckedIOException("Could not read the upload from object storage", storeTrouble);
        }
    }

    /**
     * Step 2 and 3: the browser says it is done. The store's own part list is checked against what
     * was promised, the parts are joined, and then, in one transaction, the source is bound and the
     * event is written to the outbox. Calling this again for the same upload returns the same result.
     */
    public AssetView complete(UUID assetId, String uploadId) {
        AssetRow asset = assets.find(assetId).orElseThrow(() -> new NoSuchElementException("No media asset " + assetId));
        requireNotDeleted(asset);
        if (uploadId == null || !uploadId.equals(asset.uploadId())) {
            throw new IllegalStateException("This is not the asset's current upload session");
        }
        if (!"UPLOADING".equals(asset.status())) return view(asset);   // already confirmed: a retried request
        requireNotExpired(asset);
        requireStorageBinding(asset);

        OriginalsStore.StoredObject stored;
        try {
            stored = joinParts(asset);
        } catch (IOException storeTrouble) {
            throw new UncheckedIOException("Could not complete the upload in object storage", storeTrouble);
        }

        OperationContext context = OperationContext.current();
        String profile = properties.profileVersion();
        Boolean bound = tx.execute(status -> {
            String generation = asset.originalStorageEndpoint() == null ? null : stored.generation();
            if (!assets.bindSource(assetId, uploadId, stored.etag(), stored.size(), profile, generation)) return false;
            AssetRow current = assets.find(assetId).orElseThrow();
            announce(current, context);
            return true;
        });
        if (Boolean.TRUE.equals(bound)) {
            log.info("Media upload complete asset={} bytes={}; processing announced", assetId, stored.size());
            relay.kick();
        }
        return view(assets.find(assetId).orElseThrow());
    }

    public AssetView get(UUID assetId) {
        return view(assets.find(assetId).orElseThrow(() -> new NoSuchElementException("No media asset " + assetId)));
    }

    public List<AssetView> list(String status, String kind, int limit) {
        var rows = assets.list(blankToNull(status), blankToNull(kind), Math.max(1, Math.min(limit, 200)));
        var jobs = assets.jobsFor(rows);
        var presentations = assets.presentationsFor(rows);
        return rows.stream().map(row -> view(row, jobs.getOrDefault(row.id(), List.of()),
                presentations.getOrDefault(row.id(), List.of()))).toList();
    }

    /** Puts a failed asset back in the queue with the source it already has, and announces it again. */
    public AssetView retry(UUID assetId) {
        AssetRow asset = assets.find(assetId).orElseThrow(() -> new NoSuchElementException("No media asset " + assetId));
        requireNotDeleted(asset);
        if (!"AVAILABLE".equals(asset.originalStatus()))
            throw new IllegalStateException("The original is unavailable; upload a new asset instead");
        if (!"FAILED".equals(asset.status()) || asset.sourceVersion() < 1) {
            throw new IllegalStateException("Only an asset whose processing failed can be retried; upload again instead");
        }
        OperationContext context = OperationContext.current();
        Boolean reopened = tx.execute(status -> {
            if (!assets.reopenFailed(assetId, asset.sourceVersion())) return false;
            announce(assets.find(assetId).orElseThrow(), context);
            return true;
        });
        if (Boolean.TRUE.equals(reopened)) relay.kick();
        return view(assets.find(assetId).orElseThrow());
    }

    /** Called by the janitor for sessions that ran out of time: frees the parts and closes the asset. */
    public void expire(AssetRow asset) {
        try {
            if (asset.storageUploadId() != null) originals.abortMultipartUpload(asset.uploadKey(), asset.storageUploadId());
        } catch (IOException storeTrouble) {
            log.warn("Could not abort expired upload {}: {}", asset.id(), storeTrouble.getMessage());
            return;   // try again on the next sweep rather than leave parts behind
        }
        if (assets.failUpload(asset.id(), asset.uploadId(), "upload_expired", "The upload was not finished in time")) {
            log.info("Expired unfinished media upload asset={}", asset.id());
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private OriginalsStore.StoredObject joinParts(AssetRow asset) throws IOException {
        if (asset.storageUploadId() != null) {
            List<OriginalsStore.Part> parts = new ArrayList<>(originals.listParts(asset.uploadKey(), asset.storageUploadId()));
            parts.sort(Comparator.comparingInt(OriginalsStore.Part::number));
            long total = parts.stream().mapToLong(OriginalsStore.Part::size).sum();
            if (!parts.isEmpty() && (parts.size() != asset.partCount() || total != asset.sizeBytes())) {
                throw new IllegalStateException("The upload is incomplete: " + parts.size() + " of " + asset.partCount()
                        + " parts, " + total + " of " + asset.sizeBytes() + " bytes");
            }
            for (int i = 0; i < parts.size(); i++) {
                OriginalsStore.Part part = parts.get(i);
                long expected = Math.min(asset.partSizeBytes(), asset.sizeBytes() - i * asset.partSizeBytes());
                if (part.number() != i + 1 || part.size() != expected) {
                    throw new IllegalStateException("The upload has an invalid part sequence or part size");
                }
            }
            // An empty list can also mean an earlier call already joined them; the HEAD below settles it.
            if (!parts.isEmpty()) originals.completeMultipartUpload(asset.uploadKey(), asset.storageUploadId(), parts);
        }
        OriginalsStore.StoredObject stored = originals.head(asset.uploadKey())
                .orElseThrow(() -> new IllegalStateException("The upload is incomplete: no parts have been received"));
        if (stored.size() != asset.sizeBytes()) {
            throw new IllegalStateException("The stored file has " + stored.size() + " bytes, expected " + asset.sizeBytes());
        }
        return stored;
    }

    /** Writes the event to the outbox. Runs inside the caller's transaction. */
    private void announce(AssetRow asset, OperationContext context) {
        MediaWorkEvent event = new MediaWorkEvent(UUID.randomUUID().toString(), Instant.now(), context.traceId(),
                context.correlationId(), asset.id().toString(), 1, MediaWorkEvent.SOURCE_TYPE, asset.id().toString(),
                asset.sourceVersion(), "media:" + asset.id() + ":" + asset.sourceVersion() + ":" + asset.profileVersion(),
                MediaWorkEvent.JOB_TYPE, asset.sourceKey(), asset.profileVersion());
        try {
            assets.insertOutbox(event.eventId(), "media.asset.uploaded", asset.id(), asset.sourceVersion(),
                    event.partitionKey(), mapper.writeValueAsString(event));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private AssetRow requireOpenUpload(UUID assetId, String uploadId) {
        AssetRow asset = assets.find(assetId).orElseThrow(() -> new NoSuchElementException("No media asset " + assetId));
        requireNotDeleted(asset);
        if (!"UPLOADING".equals(asset.status()) || uploadId == null || !uploadId.equals(asset.uploadId())
                || asset.storageUploadId() == null) {
            throw new IllegalStateException("This upload session is no longer open");
        }
        requireNotExpired(asset);
        requireStorageBinding(asset);
        return asset;
    }

    private void requireStorageBinding(AssetRow asset) {
        // Legacy sessions may finish, but are not made deletion-eligible by guessing their original location.
        if (asset.originalStorageEndpoint() != null && (!asset.originalStorageEndpoint().equals(originals.storageEndpoint())
                || !java.util.Objects.equals(asset.originalStorageBucket(), originals.storageBucket())))
            throw new IllegalStateException("Original storage configuration changed since upload");
    }

    private static void requireNotExpired(AssetRow asset) {
        if (asset.uploadExpiresAt() == null || !asset.uploadExpiresAt().isAfter(Instant.now())) {
            throw new IllegalStateException("This upload session has expired; start a new upload");
        }
    }

    private static void requireNotDeleted(AssetRow asset) {
        if (asset.deletedAt() != null) throw new IllegalStateException("This media asset was deleted");
    }

    private UploadSession session(AssetRow asset, List<Integer> uploaded) {
        requireNotExpired(asset);
        long remainingSeconds = Duration.between(Instant.now(), asset.uploadExpiresAt()).toSeconds();
        Duration ttl = Duration.ofSeconds(Math.max(1, Math.min(properties.upload().urlTtlSeconds(), remainingSeconds)));
        List<PartUrl> parts = new ArrayList<>();
        for (int number = 1; number <= asset.partCount(); number++) {
            parts.add(new PartUrl(number,
                    originals.presignPart(asset.uploadKey(), asset.storageUploadId(), number, ttl).toString()));
        }
        return new UploadSession(asset.id(), asset.uploadId(), asset.partSizeBytes(), asset.partCount(), uploaded,
                parts, asset.uploadExpiresAt(), asset.originalFilename(), asset.contentType(), asset.sizeBytes());
    }

    private AssetView view(AssetRow asset) {
        return view(asset, asset.sourceVersion() < 1 ? List.of() : assets.jobs(asset.id(), asset.sourceVersion()),
                assets.presentations(asset.id()));
    }

    private AssetView view(AssetRow asset, List<MediaAssetStore.JobRow> jobRows, List<String> presentations) {
        JsonNode manifest = null;
        if (asset.deletedAt() == null && "READY".equals(asset.status()) && asset.manifestJson() != null) {
            try {
                manifest = mapper.readTree(asset.manifestJson());
            } catch (IOException unreadable) {
                log.warn("Manifest of asset {} is not valid JSON", asset.id());
            }
        }
        List<JobView> jobs = jobRows.stream()
                .map(job -> new JobView(job.jobType(), job.rendition(), job.status(), job.attempt(), job.lastError(),
                        job.maxAttempts(), job.startedAt(), job.finishedAt()))
                .toList();
        JsonNode probe = null;
        try { if (asset.probeJson() != null) probe = mapper.readTree(asset.probeJson()); }
        catch (IOException ignored) { log.warn("Invalid probe for {}", asset.id()); }
        java.util.ArrayList<UsageView> usages = new java.util.ArrayList<>();
        for (String json : presentations) {
            try {
                JsonNode item = mapper.readTree(json);
                String href = item.path("href").asText();
                if (href.startsWith("/") && !href.startsWith("//")) {
                    usages.add(new UsageView(item.path("title").asText("Article"), href, 820));
                }
                usages.add(new UsageView(item.path("contentId").asText().startsWith("blog:")
                        ? "Home / Tech blogs" : item.path("id").asText().equals("yuqi-intro")
                        ? "Home / Introduction" : "Home / Projects", "/", 600));
            } catch (IOException ignored) { log.warn("Invalid presentation for {}", asset.id()); }
        }
        return new AssetView(asset.id(), asset.kind(), asset.deletedAt() == null ? asset.status() : "DELETED", asset.title(), asset.originalFilename(),
                asset.contentType(), asset.sizeBytes(), asset.sourceVersion(), asset.failureCode(),
                asset.failureMessage(), manifest, jobs, asset.createdAt(), asset.updatedAt(), probe, usages,
                asset.description(), asset.metadataVersion(), asset.deletedAt(), asset.originalStatus(), asset.originalDeletedAt());
    }

    /** Only the file name's last segment, reduced to characters that are safe in an object key. */
    static String safeName(String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        if (name.isBlank()) name = "upload";
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Kept for callers that want the limits without reading configuration. */
    public Map<String, Object> limits() {
        MediaPipelineProperties.Upload limits = properties.upload();
        return Map.of("maxVideoBytes", limits.maxVideoBytes(), "maxImageBytes", limits.maxImageBytes(),
                "videoTypes", VIDEO_TYPES, "imageTypes", IMAGE_TYPES);
    }
}
