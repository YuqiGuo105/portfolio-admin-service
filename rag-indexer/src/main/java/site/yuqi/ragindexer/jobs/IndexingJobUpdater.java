package site.yuqi.ragindexer.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Same shape as the search-indexer version — updates the shared indexing_jobs table. */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingJobUpdater {

    private final JdbcTemplate jdbc;

    @Transactional
    public void markProcessing(UUID id) {
        Instant now = Instant.now();
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
        Instant now = Instant.now();
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
        Instant now = Instant.now();
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
