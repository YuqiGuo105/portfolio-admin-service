package site.yuqi.admin.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

/** SQL is the durable event source; OpenSearch is a rebuildable projection. */
@Service
@RequiredArgsConstructor
public class OperationEventJournal {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OperationTimelineProjector projector;
    public void append(OperationEvent event) {
        try {
            jdbc.update("insert into operation_event_journal(event_id,payload) values (?,?) on conflict do nothing",
                    event.eventId(),mapper.writeValueAsString(event));
        } catch(Exception e) { throw new IllegalStateException("Operation audit persistence failed",e); }
    }
    @Transactional
    public int drain() {
        var rows=jdbc.queryForList("select event_id,payload,attempts from operation_event_journal where projected_at is null and next_retry_at<=now() and attempts<8 order by created_at limit 20 for update skip locked");
        int projected=0;
        for(Map<String,Object> row:rows) {
            try {
                projector.project(mapper.readValue((String)row.get("payload"),OperationEvent.class));
                jdbc.update("update operation_event_journal set projected_at=now() where event_id=?",row.get("event_id"));
                projected++;
            } catch(Exception failed) {
                int attempt=((Number)row.get("attempts")).intValue()+1;
                jdbc.update("update operation_event_journal set attempts=?,next_retry_at=? where event_id=?",attempt,
                        java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(Math.min(3600,30L<<attempt))),row.get("event_id"));
            }
        }
        return projected;
    }
}
