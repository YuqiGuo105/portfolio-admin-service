package site.yuqi.admin.mediapipeline;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.dto.ApiError;
import site.yuqi.admin.security.AdminPrincipal;

import java.util.UUID;

/** Both operations inherit the ADMIN-only media authorization and private/no-store headers. */
@RestController
@RequestMapping("/api/admin/media")
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaOriginalDeletionController {
    private final MediaOriginalDeletionService service;

    public MediaOriginalDeletionController(MediaOriginalDeletionService service) {
        this.service = service;
    }

    @GetMapping("/{id}/original-deletion-check")
    public MediaOriginalDeletionService.Check check(@PathVariable UUID id) {
        return service.check(id);
    }

    @DeleteMapping("/{id}/original")
    public MediaUploadService.AssetView delete(@PathVariable UUID id,
                                               @RequestParam(required = false) Integer expectedVersion,
                                               HttpServletRequest request) {
        if (expectedVersion == null || expectedVersion < 0)
            throw new IllegalArgumentException("expectedVersion from media.get is required");
        return service.delete(id, expectedVersion, AdminPrincipal.from(request));
    }

    @ExceptionHandler(MediaOriginalDeletionService.StorageDeletionException.class)
    public ResponseEntity<ApiError> storageFailure(MediaOriginalDeletionService.StorageDeletionException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("original_delete_failed", error.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest().body(ApiError.of("bad_request", "Invalid media id or expectedVersion"));
    }
}
