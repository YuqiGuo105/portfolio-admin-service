package site.yuqi.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Durable command admission. An expired dispatch is uncertain, never an automatic replay. */
@Service
@RequiredArgsConstructor
public class McpOperationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional
    public Map<String, Object> claim(String principal, String tool, String key, String hash) {
        require(principal, 320); require(tool, 200); require(key, 200);
        if (key.length() < 8 || hash == null || !hash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid idempotency key or request hash");
        UUID id = UUID.nameUUIDFromBytes((principal + "\0" + tool + "\0" + key).getBytes(StandardCharsets.UTF_8));
        UUID token = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into mcp_operations(operation_id,principal,tool,idempotency_key,request_hash,state,lease_token,lease_until)
                values (?,?,?,?,?,'RUNNING',?,?) on conflict do nothing
                """, id, principal, tool, key, hash, token, Timestamp.from(Instant.now().plusSeconds(120)));
        Map<String, Object> row = locked(id, principal);
        if (!hash.equals(row.get("request_hash"))) throw new IllegalStateException("IDEMPOTENCY_CONFLICT: key belongs to a different payload");
        boolean dispatch = inserted == 1;
        if (!dispatch && "RETRYABLE".equals(row.get("state"))) {
            jdbc.update("update mcp_operations set state='RUNNING',attempt=attempt+1,response_json=null,http_status=null,lease_token=?,lease_until=?,updated_at=now() where operation_id=?",
                    token, Timestamp.from(Instant.now().plusSeconds(120)), id);
            row = locked(id, principal);
            dispatch = true;
        }
        if (dispatch) transition(id, "RUNNING", ((Number) row.get("attempt")).intValue());
        Map<String, Object> result = view(row);
        result.put("dispatch", dispatch);
        if (dispatch) result.put("leaseToken", token.toString());
        else if (row.get("response_json") != null) result.put("response", decode((String) row.get("response_json")));
        return result;
    }

    @Transactional
    public Map<String, Object> complete(UUID id, String principal, UUID token, String state, int status, Map<String,Object> response) {
        if (!Set.of("SUCCEEDED", "FAILED_FINAL", "RETRYABLE", "UNKNOWN").contains(state))
            throw new IllegalArgumentException("Invalid terminal state");
        Map<String,Object> row = locked(id, principal);
        if (!token.equals(row.get("lease_token"))) throw new IllegalStateException("STALE_OPERATION_LEASE");
        if (!"RUNNING".equals(row.get("state"))) return view(row);
        jdbc.update("update mcp_operations set state=?,http_status=?,response_json=?,updated_at=now() where operation_id=?",
                state, status, encode(response), id);
        transition(id, state, ((Number) row.get("attempt")).intValue());
        return view(locked(id, principal));
    }

    @Transactional(readOnly = true)
    public Map<String,Object> status(UUID id, String principal) {
        var rows = jdbc.queryForList("select * from mcp_operations where operation_id=? and principal=?", id, principal);
        if (rows.isEmpty()) throw new NoSuchElementException("Operation not found");
        return view(rows.get(0));
    }

    @Transactional(readOnly = true)
    public Map<String,Object> list(String principal, int limit) {
        return Map.of("items", jdbc.queryForList("select * from mcp_operations where principal=? order by created_at desc limit ?",
                principal, Math.max(1, Math.min(100, limit))).stream().map(this::view).toList());
    }

    @Transactional(readOnly = true)
    public Map<String,Object> timeline(UUID id, String principal) {
        Map<String,Object> result = status(id, principal);
        result.put("transitions", jdbc.queryForList("select state,attempt,occurred_at from mcp_operation_transitions where operation_id=? order by id", id));
        return result;
    }

    private Map<String,Object> locked(UUID id, String principal) {
        var rows = jdbc.queryForList("select * from mcp_operations where operation_id=? and principal=? for update", id, principal);
        if (rows.isEmpty()) throw new NoSuchElementException("Operation not found");
        return rows.get(0);
    }

    private void transition(UUID id, String state, int attempt) {
        jdbc.update("insert into mcp_operation_transitions(operation_id,state,attempt) values (?,?,?)", id, state, attempt);
    }

    private Map<String,Object> view(Map<String,Object> row) {
        String state = (String) row.get("state");
        if ("RUNNING".equals(state) && ((Timestamp)row.get("lease_until")).toInstant().isBefore(Instant.now())) state = "UNKNOWN";
        var result = new LinkedHashMap<String,Object>();
        result.put("operationId", row.get("operation_id").toString());
        result.put("tool", row.get("tool")); result.put("idempotencyKey", row.get("idempotency_key"));
        result.put("state", state); result.put("attempt", row.get("attempt"));
        result.put("retryable", "RETRYABLE".equals(state));
        result.put("safeToRetry", "RETRYABLE".equals(state) || "SUCCEEDED".equals(state));
        result.put("ambiguousOutcome", "UNKNOWN".equals(state));
        result.put("retryAfterMs", "RETRYABLE".equals(state) ? 5000 : 0);
        result.put("nextAction", switch(state) {
            case "RETRYABLE" -> "RETRY_SAME_KEY_AND_PAYLOAD";
            case "RUNNING" -> "POLL_STATUS";
            case "UNKNOWN" -> "VERIFY_DOWNSTREAM_BEFORE_RETRY";
            case "SUCCEEDED" -> "VERIFY_ASYNC_EFFECTS_IF_ANY";
            default -> "FIX_REQUEST_OR_CONTACT_ADMIN";
        });
        result.put("createdAt", row.get("created_at")); result.put("updatedAt", row.get("updated_at"));
        if (row.get("http_status") != null) result.put("httpStatus", row.get("http_status"));
        return result;
    }

    private static void require(String value, int max) {
        if (value == null || value.isBlank() || value.length()>max || value.indexOf('\0')>=0)
            throw new IllegalArgumentException("Invalid operation identity");
    }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid operation response"); }
    }
    private Map<String,Object> decode(String value) {
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("Stored response unavailable"); }
    }
}
