package site.yuqi.admin.mediapipeline;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import site.yuqi.admin.dto.ApiError;
import site.yuqi.admin.security.AdminPrincipal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/media/presentation-jobs")
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaPresentationJobsController {
    private final MediaPresentationJobs jobs;
    public MediaPresentationJobsController(MediaPresentationJobs jobs) { this.jobs = jobs; }
    @PostMapping public MediaPresentationJobs.View prepare(@RequestBody MediaPresentationJobs.Prepare request, HttpServletRequest http) {
        return jobs.prepare(request, AdminPrincipal.from(http));
    }
    @GetMapping public List<MediaPresentationJobs.View> list() { return jobs.list(); }
    @GetMapping("/{id}") public MediaPresentationJobs.View get(@PathVariable UUID id) { return jobs.get(id); }
    @PostMapping("/{id}/upload") public ResponseEntity<?> upload(@PathVariable UUID id,
            @RequestBody MediaUploadService.StartUpload request, HttpServletRequest http) {
        Object principal = http.getAttribute(AdminPrincipal.ATTR);
        if (!(principal instanceof String email) || !email.contains("@"))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("forbidden",
                    "Use the administrator sign-in page to approve this upload"));
        return ResponseEntity.ok(jobs.upload(id, request, email, AdminPrincipal.from(http)));
    }
    @PostMapping("/{id}/cancel") public MediaPresentationJobs.View cancel(@PathVariable UUID id, HttpServletRequest http) {
        return jobs.cancel(id, AdminPrincipal.from(http));
    }
}
