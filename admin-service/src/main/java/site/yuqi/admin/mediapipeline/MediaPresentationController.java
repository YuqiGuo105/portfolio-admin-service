package site.yuqi.admin.mediapipeline;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import site.yuqi.admin.security.AdminPrincipal;

@RestController
@RequestMapping("/api/admin/media/presentations/{placementKey}")
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaPresentationController {
    private final MediaPresentationService presentations;
    public MediaPresentationController(MediaPresentationService presentations) { this.presentations = presentations; }

    @GetMapping
    public MediaPresentationService.View get(@PathVariable String placementKey) {
        return presentations.get(placementKey);
    }

    @PutMapping
    public MediaPresentationService.View replace(@PathVariable String placementKey,
            @RequestBody MediaPresentationService.Replace request, HttpServletRequest http) {
        return presentations.replace(placementKey, request, AdminPrincipal.from(http));
    }

    @DeleteMapping
    public MediaPresentationService.View disable(@PathVariable String placementKey,
            @RequestParam String expectedRevision, HttpServletRequest http) {
        return presentations.disable(placementKey, expectedRevision, AdminPrincipal.from(http));
    }
}
