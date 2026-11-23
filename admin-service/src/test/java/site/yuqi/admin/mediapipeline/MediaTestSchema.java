package site.yuqi.admin.mediapipeline;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

/**
 * A throwaway schema in a local PostgreSQL, loaded with the real V11 migration. Opt-in through
 * MEDIA_TEST_JDBC_URL; never a production or Supabase connection.
 */
final class MediaTestSchema implements AutoCloseable {

    static final String ENV = "MEDIA_TEST_JDBC_URL";
    static final String LOCAL_ONLY = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-z_]+_test";

    private final JdbcTemplate admin;
    private final String schema = "media_api_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    final JdbcTemplate jdbc;
    final PlatformTransactionManager transactions;

    MediaTestSchema() {
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
        // Match Supabase defaults so migrations must explicitly remove inherited write grants.
        admin.execute("alter default privileges in schema " + schema
                + " grant all on tables to anon, authenticated");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V11__media_pipeline.sql"),
                new ClassPathResource("db/migration/V12__durable_media_wake.sql"),
                new ClassPathResource("db/migration/V13__media_presentations.sql"),
                new ClassPathResource("db/migration/V14__media_public_views_read_only.sql")).execute(dataSource);
    }

    int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    @Override
    public void close() {
        admin.execute("drop schema " + schema + " cascade");
    }
}
