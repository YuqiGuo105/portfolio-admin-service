package site.yuqi.admin.media;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.dto.ContentCoverUploadRequest;
import site.yuqi.admin.dto.ContentCoverUploadResponse;
import site.yuqi.admin.service.ContentService;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContentCoverService {

    private final ContentService contentService;
    private final CoverImageLoader imageLoader;
    private final ContentCoverStorage storage;

    public ContentCoverUploadResponse upload(SourceType type, String sourceId,
                                             ContentCoverUploadRequest request, String actor) {
        contentService.getOrThrow(type, sourceId);
        CoverImageLoader.LoadedCover image = imageLoader.load(request);
        String sha256 = sha256(image.bytes());
        String objectPath = type.name().toLowerCase() + "/" + safeSourceId(sourceId)
                + "/" + sha256 + "." + image.extension();

        ContentCoverStorage.StoredCover stored = storage.put(objectPath, image.bytes(), image.mimeType());
        try {
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("imageUrl", stored.publicUrl());
            if (type == SourceType.PROJECT) patch.put("coverVariant", "IMAGE");
            String note = request.getChangeNote() == null || request.getChangeNote().isBlank()
                    ? "Upload content cover via MCP"
                    : request.getChangeNote();
            NormalizedContent updated = contentService.update(type, sourceId, patch, false, actor, note);
            return ContentCoverUploadResponse.builder()
                    .sourceType(type.name())
                    .sourceId(sourceId)
                    .bucket(storage.bucket())
                    .objectPath(stored.objectPath())
                    .imageUrl(stored.publicUrl())
                    .originalFileName(safeFileName(request.getFileName()))
                    .sha256(sha256)
                    .mimeType(image.mimeType())
                    .sizeBytes(image.bytes().length)
                    .deduplicated(!stored.created())
                    .content(updated)
                    .build();
        } catch (RuntimeException ex) {
            if (stored.created()) storage.delete(stored.objectPath());
            throw ex;
        }
    }

    private static String safeSourceId(String sourceId) {
        String safe = sourceId == null ? "" : sourceId.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) throw new IllegalArgumentException("sourceId is required.");
        return safe;
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        String normalized = fileName.replace('\\', '/');
        String baseName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (baseName.isBlank()) return null;
        return baseName.length() <= 255 ? baseName : baseName.substring(0, 255);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}
