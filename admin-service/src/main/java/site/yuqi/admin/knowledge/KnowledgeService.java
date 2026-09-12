package site.yuqi.admin.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.service.AuditLogService;
import site.yuqi.admin.service.IndexingJobService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final AuditLogService audit;
    private final IndexingJobService jobs;
    private final KnowledgeIndexDispatcher dispatcher;

    @Transactional(readOnly = true)
    public Map<String, Object> list(String query, String scope, String status, int limit, int offset) {
        if (query == null || query.length() > 300 || query.indexOf('\0') >= 0 || limit < 1 || limit > 100 || offset < 0 || offset > 100000)
            throw new IllegalArgumentException("Invalid search or pagination");
        if (!Set.of("ALL", "OWNED", "INDEXED").contains(scope)
                || !Set.of("ALL", "DRAFT", "ACTIVE", "ARCHIVED", "SUPERSEDED", "LEGACY").contains(status))
            throw new IllegalArgumentException("Invalid knowledge filter");
        return repository.list(query, scope, status, limit, offset);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) { return view(require(id, false)); }

    @Transactional(readOnly = true)
    public Map<String, Object> getBatch(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 25 || ids.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("ids must contain 1-25 knowledge UUIDs");
        List<UUID> unique = ids.stream().distinct().toList();
        var records = repository.findBatch(unique);
        var indexing = repository.indexingBatch(unique);
        var items = unique.stream().map(id -> records.containsKey(id)
                ? Map.of("id", id, "found", true, "record", view(records.get(id), indexing.getOrDefault(id.toString(), Map.of("status", "NOT_INDEXED"))))
                : Map.of("id", id, "found", false)).toList();
        return Map.of("items", items, "batch", Map.of("requested", unique.size(), "found", records.size(), "maxSize", 25));
    }

    @Transactional
    public Map<String, Object> create(KnowledgeMutation input, String key, String actor) {
        KnowledgeMutation body = input.validated();
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,200}"))
            throw new IllegalArgumentException("A stable Idempotency-Key of 8-200 characters is required");
        UUID id = UUID.nameUUIDFromBytes(("knowledge\0" + actor + "\0" + key).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> meta = metadata(Map.of(), body, 1);
        meta.put("create_hash", fingerprint(body));
        if (!repository.insert(id, body.content(), meta)) {
            var existing = require(id, true);
            if (!fingerprint(body).equals(existing.metadata().get("create_hash")))
                throw new IllegalStateException("Idempotency key was already used with a different request");
            return view(existing);
        }
        if ("ACTIVE".equals(body.status())) dispatcher.afterCommit(jobs.enqueueKnowledge(id.toString(), 1));
        var created = require(id, false);
        audit.log(actor, AuditAction.CREATE, "KNOWLEDGE", id.toString(), 1, null, snapshot(created));
        return view(created);
    }

    @Transactional
    public Map<String, Object> update(UUID id, KnowledgeMutation input, String actor) {
        KnowledgeMutation body = input.validated();
        var old = require(id, true);
        editable(old);
        revision(old, body.expectedRevision());
        int version = version(old) + 1;
        Map<String, Object> meta = metadata(old.metadata(), body, version);
        repository.update(id, body.content(), meta);
        repository.invalidateDerived(id);
        if ("ACTIVE".equals(body.status())) dispatcher.afterCommit(jobs.enqueueKnowledge(id.toString(), version));
        var updated = require(id, false);
        audit.log(actor, AuditAction.UPDATE, "KNOWLEDGE", id.toString(), version, snapshot(old), snapshot(updated));
        return view(updated);
    }

    @Transactional
    public Map<String, Object> delete(UUID id, String expectedRevision, String actor) {
        var old = require(id, true);
        editable(old);
        revision(old, expectedRevision);
        repository.delete(id);
        audit.log(actor, AuditAction.DELETE, "KNOWLEDGE", id.toString(), version(old), snapshot(old), null);
        return Map.of("id", id, "deleted", true, "indexing", Map.of("status", "REMOVED"));
    }

    private KnowledgeRepository.Entry require(UUID id, boolean lock) {
        return repository.find(id, lock).orElseThrow(() -> new NoSuchElementException("Knowledge record not found"));
    }
    private static void editable(KnowledgeRepository.Entry row) {
        if (!row.editable()) throw new IllegalStateException("Generated chunks are read-only. Edit their original article, project or knowledge record and reindex it.");
    }
    private static void revision(KnowledgeRepository.Entry row, String expected) {
        if (expected == null || !expected.matches("[a-f0-9]{32}"))
            throw new IllegalArgumentException("expectedRevision from knowledge.get is required");
        if (!row.revision().equals(expected))
            throw new IllegalStateException("This knowledge record changed. Reload it before saving or deleting.");
    }
    private static int version(KnowledgeRepository.Entry row) {
        Object value = row.metadata().get("management_version");
        return value instanceof Number number ? number.intValue() : 0;
    }
    private static Map<String, Object> metadata(Map<String, Object> previous, KnowledgeMutation body, int version) {
        var meta = new LinkedHashMap<>(previous);
        meta.put("type", "chat_qa");
        meta.put("title", body.title()); meta.put("question", body.question());
        meta.put("status", body.status()); meta.put("answer_visibility", body.answerVisibility());
        meta.put("source_requires_login", true);
        // Canonical records are never vector results; approved derived chunks are indexed separately.
        meta.put("retrieval_eligible", false);
        meta.put("evidence_review", "ACTIVE".equals(body.status()) ? "approved" : "pending");
        meta.put("management_version", version); meta.put("updated_at", Instant.now().toString());
        return meta;
    }
    private Map<String, Object> view(KnowledgeRepository.Entry row) {
        return view(row, row.editable() && "ACTIVE".equals(row.metadata().get("status"))
                ? repository.indexing(row.id()) : Map.of());
    }
    private Map<String, Object> view(KnowledgeRepository.Entry row, Map<String, Object> indexing) {
        var result = snapshot(row);
        result.put("id", row.id()); result.put("revision", row.revision());
        result.put("editable", row.editable()); result.put("createdAt", row.createdAt());
        result.put("indexing", !row.editable() ? Map.of("status", "PROJECTION")
                : "ACTIVE".equals(row.metadata().get("status")) ? indexing
                : Map.of("status", "DISABLED"));
        return result;
    }
    private static Map<String, Object> snapshot(KnowledgeRepository.Entry row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("content", row.content()); result.put("metadata", row.metadata());
        return result;
    }
    private static String fingerprint(KnowledgeMutation body) {
        try {
            String text = String.join("\0", body.title(), body.question(), body.content(), body.status(), body.answerVisibility());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
