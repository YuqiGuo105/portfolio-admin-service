package site.yuqi.media.support;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A throwaway schema in a local PostgreSQL, loaded with the real migration.
 *
 * <p>Opt-in through MEDIA_TEST_JDBC_URL and never a production or Supabase connection: the tests
 * that use it are annotated to run only against 127.0.0.1. The lease, skip-locked and
 * compare-and-set statements under test are PostgreSQL behaviour, so they are tested on PostgreSQL.
 */
public final class TestDatabase implements AutoCloseable {

    public static final String ENV = "MEDIA_TEST_JDBC_URL";
    public static final String LOCAL_ONLY = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-z_]+_test";

    private final JdbcTemplate admin;
    private final String schema = "media_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    public final JdbcTemplate jdbc;
    public final PlatformTransactionManager transactions;

    public TestDatabase() {
        String url = System.getenv(ENV);
        String user = System.getenv().getOrDefault("MEDIA_TEST_JDBC_USER", "alerts_test");
        String password = System.getenv().getOrDefault("MEDIA_TEST_JDBC_PASSWORD", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("""
                do $$ begin
                    if not exists (select from pg_roles where rolname = 'anon') then create role anon nologin; end if;
                    if not exists (select from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
                end $$""");
        admin.execute("create schema " + schema);

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        new ResourceDatabasePopulator(new FileSystemResource(migration())).execute(dataSource);
        // Owned by V10 in the Content Service; the Media Service only inserts into it.
        jdbc.execute("""
                create table indexing_kafka_quarantine (
                    worker varchar(32) not null, topic varchar(255) not null, partition_id integer not null,
                    record_offset bigint not null, payload text, error_type varchar(255),
                    created_at timestamptz not null default now(),
                    primary key (worker, topic, partition_id, record_offset))""");
    }

    /** The schema is owned by the Content Service's Flyway; the Media Service tests load that same file. */
    private static Path migration() {
        String file = "admin-service/src/main/resources/db/migration/V11__media_pipeline.sql";
        for (Path base : new Path[]{Path.of(".."), Path.of(".")}) {
            if (Files.exists(base.resolve(file))) return base.resolve(file);
        }
        throw new IllegalStateException("cannot find " + file + " from " + Path.of("").toAbsolutePath());
    }

    /** An asset whose upload has just been bound: PROCESSING, source version 1, no jobs yet. */
    public UUID processingAsset(String kind) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into media_assets (id, kind, status, original_filename, content_type, size_bytes,
                                          source_version, source_key, source_bytes, profile_version,
                                          processing_started_at, created_by)
                values (?, ?, 'PROCESSING', 'clip.mp4', 'video/mp4', 1000, 1, ?, 1000, 'v1', now(), 'test')
                """, id, kind, "originals/" + id + "/u1/clip.mp4");
        return id;
    }

    public String assetStatus(UUID id) {
        return jdbc.queryForObject("select status from media_assets where id = ?", String.class, id);
    }

    public int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    @Override
    public void close() {
        admin.execute("drop schema " + schema + " cascade");
    }
}
