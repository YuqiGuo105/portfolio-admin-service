package site.yuqi.admin.mediapipeline;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.mediapipeline.MediaUploadService.AssetView;
import site.yuqi.admin.mediapipeline.MediaUploadService.StartUpload;
import site.yuqi.admin.mediapipeline.MediaUploadService.UploadSession;
import site.yuqi.admin.security.AdminPrincipal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Upload API for images and videos. It sits behind the same admin authentication as every other
 * {@code /api/admin} route; under the existing rules the POST routes need the ADMIN role and the
 * GET routes any admin.
 */
@RestController
@RequestMapping("/api/admin/media")
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaAdminController {

    public record CompleteUpload(String uploadId) {
    }

    private final MediaUploadService uploads;

    public MediaAdminController(MediaUploadService uploads) {
        this.uploads = uploads;
    }

    /** Step 1. Body: filename, contentType, sizeBytes, optional title. Returns one signed URL per part. */
    @PostMapping("/uploads")
    public ResponseEntity<UploadSession> start(@RequestBody StartUpload request, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(uploads.start(request, AdminPrincipal.from(http)));
    }

    /** Resume an interrupted upload: the parts already stored, and fresh URLs. */
    @PostMapping("/{id}/upload-parts")
    public UploadSession resume(@PathVariable UUID id, @RequestBody CompleteUpload request) {
        return uploads.resume(id, request.uploadId());
    }

    /** Step 2. Confirms the original is completely stored and starts processing. Safe to repeat. */
    @PostMapping("/{id}/upload-complete")
    public AssetView complete(@PathVariable UUID id, @RequestBody CompleteUpload request) {
        return uploads.complete(id, request.uploadId());
    }

    @GetMapping("/{id}")
    public AssetView get(@PathVariable UUID id) {
        return uploads.get(id);
    }

    @GetMapping
    public List<AssetView> list(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String kind,
                                @RequestParam(defaultValue = "50") int limit) {
        return uploads.list(status, kind, limit);
    }

    @PostMapping("/{id}/retry")
    public AssetView retry(@PathVariable UUID id) {
        return uploads.retry(id);
    }

    @GetMapping("/limits")
    public Map<String, Object> limits() {
        return uploads.limits();
    }
}
