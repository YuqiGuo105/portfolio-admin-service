package site.yuqi.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import site.yuqi.admin.knowledge.KnowledgeMutation;
import site.yuqi.admin.knowledge.KnowledgeService;
import site.yuqi.admin.security.AdminPrincipal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/knowledge")
@RequiredArgsConstructor
public class KnowledgeAdminController {
    private final KnowledgeService service;

    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public org.springframework.http.ResponseEntity<Map<String, String>> malformedRequest(Exception exception) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "message", "Invalid knowledge request parameters or JSON body"));
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "OWNED") String scope, @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "25") int limit, @RequestParam(defaultValue = "0") int offset) {
        return service.list(query, scope, status, limit, offset);
    }
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) { return service.get(id); }
    public record BatchRead(java.util.List<UUID> ids) {}
    public record SearchFilter(String query, String scope, String status) {}
    public record SearchPage(Integer size, Integer offset) {}
    public record SearchRequest(SearchFilter filter, SearchPage page) {}

    @PostMapping("/batch-get")
    public Map<String, Object> batchGet(@RequestBody BatchRead request) { return service.getBatch(request.ids()); }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchRequest body) {
        var filter = body.filter() == null ? new SearchFilter(null, null, null) : body.filter();
        var page = body.page() == null ? new SearchPage(null, null) : body.page();
        return service.list(filter.query() == null ? "" : filter.query(), filter.scope() == null ? "OWNED" : filter.scope(),
                filter.status() == null ? "ALL" : filter.status(), page.size() == null ? 25 : page.size(), page.offset() == null ? 0 : page.offset());
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody KnowledgeMutation body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return service.create(body, key, AdminPrincipal.from(request));
    }
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody KnowledgeMutation body, HttpServletRequest request) {
        return service.update(id, body, AdminPrincipal.from(request));
    }
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id, @RequestParam String expectedRevision, HttpServletRequest request) {
        return service.delete(id, expectedRevision, AdminPrincipal.from(request));
    }
}
