package site.yuqi.admin.mediapipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "portfolio.media-pipeline.enabled", havingValue = "true")
public class MediaWakeStore {
    public record Delivery(UUID id, UUID assetId, UUID token, int attempt) { }
    private final JdbcTemplate jdbc;

    public MediaWakeStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Delivery> claim(int limit, int leaseSeconds, int maxAttempts) {
        // A terminal/superseded asset no longer needs CPU, even if the prior HTTP response was lost.
        jdbc.update("""
                update media_event_outbox o set wake_status='DONE', wake_token=null,
                    wake_lease_until=null, wake_error=null
                from media_assets a where a.id=o.asset_id and o.status='SENT'
                    and o.wake_status in ('PENDING','PROCESSING')
                    and (a.status <> 'PROCESSING' or a.source_version <> o.source_version)
                """);
        // A process can die before finish(); expired leases must still respect the retry budget.
        jdbc.update("""
                update media_event_outbox set wake_status='DLQ',wake_token=null,wake_lease_until=null,
                    wake_error='Wake retry budget exhausted after lease expiry'
                where status='SENT' and wake_status in ('PENDING','PROCESSING') and wake_attempts>=?
                  and (wake_lease_until is null or wake_lease_until<now())
                """, maxAttempts);
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                with due as (
                    select o.id from media_event_outbox o join media_assets a on a.id=o.asset_id
                    where o.status='SENT' and o.wake_status in ('PENDING','PROCESSING')
                      and o.wake_after <= now()
                      and (o.wake_lease_until is null or o.wake_lease_until < now())
                      and a.status='PROCESSING' and a.source_version=o.source_version
                    order by o.wake_after limit ? for update of o skip locked
                )
                update media_event_outbox o set wake_status='PROCESSING',
                    wake_attempts=o.wake_attempts+1, wake_token=?,
                    wake_lease_until=now()+make_interval(secs=>?)
                from due where o.id=due.id
                returning o.id,o.asset_id,o.wake_token,o.wake_attempts
                """, (rs,n) -> new Delivery(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getInt(4)), limit,token,leaseSeconds);
    }

    public boolean finish(Delivery delivery, boolean completed, int maxAttempts) {
        String status = completed ? "DONE" : delivery.attempt() >= maxAttempts ? "DLQ" : "PENDING";
        long delay = Math.min(300, 5L << Math.min(6, delivery.attempt()-1));
        return jdbc.update("""
                update media_event_outbox set wake_status=?,wake_after=now()+make_interval(secs=>?),
                    wake_token=null,wake_lease_until=null,wake_error=?
                where id=? and wake_token=? and wake_status='PROCESSING' and wake_lease_until>now()
                """,status,delay,completed ? null : "Worker unavailable or not finished",
                delivery.id(),delivery.token()) == 1;
    }

    public Map<String, Integer> counts() {
        Map<String,Integer> result = new java.util.LinkedHashMap<>();
        jdbc.query("select wake_status,count(*) from media_event_outbox where status='SENT' group by wake_status",
                rs -> { result.put(rs.getString(1), rs.getInt(2)); });
        return result;
    }
}
