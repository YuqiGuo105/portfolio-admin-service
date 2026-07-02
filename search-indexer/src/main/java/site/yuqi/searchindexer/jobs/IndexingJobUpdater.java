package site.yuqi.searchindexer.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Updates the {@code public.indexing_jobs} row owned by admin-service.
 * The indexer never inserts jobs — admin-service is the writer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingJobUpdater {

    private final JdbcTemplate jdbc;

    // PostgreSQL JDBC (42.x) does not know how to bind a java.time.Instant
    // as a positional parameter — Postgres has no dedicated Instant type, so
    // pgjdbc raises "Can't infer the SQL type to use for an instance of
    // java.time.Instant". OffsetDateTime maps naturally to timestamptz.
    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Transactional
    public void markProcessing(UUID id) {
        OffsetDateTime now = nowUtc();
        int rows = jdbc.update("""
                UPDATE public.indexing_jobs
                   SET status = 'PROCESSING',
                       started_at = ?,
                       updated_at = ?
                 WHERE id = ?
                """, now, now, id);
        if (rows == 0) log.warn("indexing_jobs row not found for id={}", id);
    }

    @Transactional
    public void markDone(UUID id) {
        OffsetDateTime now = nowUtc();
        jdbc.update("""
                UPDATE public.indexing_jobs
                   SET status = 'DONE',
                       completed_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE id = ?
                """, now, now, id);
    }

    @Transactional
    public void markFailed(UUID id, String error) {
        OffsetDateTime now = nowUtc();
        String truncated = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        jdbc.update("""
                UPDATE public.indexing_jobs
                   SET status = 'FAILED',
                       retry_count = retry_count + 1,
                       last_error = ?,
                       updated_at = ?,
                       next_retry_at = ?
                 WHERE id = ?
                """, truncated, now, now.plusSeconds(60), id);
    }
}
