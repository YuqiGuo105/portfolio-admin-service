package site.yuqi.admin.mediapipeline;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import site.yuqi.admin.security.AdminPrincipal;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/media/articles/{sourceType}/{sourceId}")
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaArticleController {
    private final MediaArticleService articles;
    public MediaArticleController(MediaArticleService articles) { this.articles = articles; }

    @GetMapping
    public MediaArticleService.ArticleView get(@PathVariable String sourceType, @PathVariable UUID sourceId) {
        return articles.get(sourceType, sourceId);
    }

    @PostMapping
    public MediaArticleService.ArticleView attach(@PathVariable String sourceType, @PathVariable UUID sourceId,
            @RequestBody MediaArticleService.Attach request, HttpServletRequest http) {
        return articles.attach(sourceType, sourceId, request, AdminPrincipal.from(http));
    }

    @DeleteMapping
    public MediaArticleService.ArticleView detach(@PathVariable String sourceType, @PathVariable UUID sourceId,
            @RequestParam UUID id, @RequestParam String expectedContentHash, HttpServletRequest http) {
        return articles.detach(sourceType, sourceId, id, expectedContentHash, AdminPrincipal.from(http));
    }
}
