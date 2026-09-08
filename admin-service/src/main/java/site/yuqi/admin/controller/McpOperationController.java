package site.yuqi.admin.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import site.yuqi.admin.service.McpOperationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/** Ledger mutation is service-to-service only; end users cannot forge completion. */
@RestController
@RequestMapping("/api/admin/mcp-operations")
public class McpOperationController {
    private final McpOperationService service;
    private final String secret;
    public McpOperationController(McpOperationService service, @Value("${portfolio.admin.secret:}") String secret) {
        this.service=service; this.secret=secret;
    }
    @PostMapping("/claim")
    public Map<String,Object> claim(@RequestHeader("X-Admin-Secret") String credential, @RequestBody Claim body) {
        authenticate(credential);
        return service.claim(body.principal(),body.tool(),body.idempotencyKey(),body.requestHash());
    }
    @PostMapping("/{id}/complete")
    public Map<String,Object> complete(@RequestHeader("X-Admin-Secret") String credential, @PathVariable UUID id, @RequestBody Completion body) {
        authenticate(credential);
        return service.complete(id,body.principal(),body.leaseToken(),body.state(),body.httpStatus(),body.response());
    }
    @GetMapping("/{id}")
    public Map<String,Object> status(@RequestHeader("X-Admin-Secret") String credential, @PathVariable UUID id, @RequestParam String principal) {
        authenticate(credential); return service.status(id, principal);
    }
    @GetMapping
    public Map<String,Object> list(@RequestHeader("X-Admin-Secret") String credential, @RequestParam String principal, @RequestParam(defaultValue="20") int limit) {
        authenticate(credential); return service.list(principal, limit);
    }
    @GetMapping("/{id}/timeline")
    public Map<String,Object> timeline(@RequestHeader("X-Admin-Secret") String credential, @PathVariable UUID id, @RequestParam String principal) {
        authenticate(credential); return service.timeline(id, principal);
    }
    private void authenticate(String actual) {
        if(secret.isBlank() || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    public record Claim(String principal,String tool,String idempotencyKey,String requestHash) {}
    public record Completion(String principal,UUID leaseToken,String state,int httpStatus,Map<String,Object> response) {}
}
