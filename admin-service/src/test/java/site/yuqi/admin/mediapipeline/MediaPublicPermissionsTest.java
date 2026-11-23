package site.yuqi.admin.mediapipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = MediaTestSchema.ENV, matches = MediaTestSchema.LOCAL_ONLY)
class MediaPublicPermissionsTest {
    @Test
    void projectionsAreReadOnlyEvenWithSupabaseDefaultGrants() {
        try (var db = new MediaTestSchema()) {
            for (String role : new String[]{"anon", "authenticated"}) {
                for (String view : new String[]{"media_public", "media_demo_catalog"}) {
                    assertThat(db.jdbc.queryForObject(
                            "select has_table_privilege(?, ?, 'SELECT')", Boolean.class, role, view)).isTrue();
                    for (String privilege : new String[]{"INSERT", "UPDATE", "DELETE", "TRUNCATE", "TRIGGER"}) {
                        assertThat(db.jdbc.queryForObject(
                                "select has_table_privilege(?, ?, ?)", Boolean.class, role, view, privilege)).isFalse();
                    }
                }
                for (String table : new String[]{"media_assets", "media_jobs", "media_event_outbox", "media_presentations"}) {
                    assertThat(db.jdbc.queryForObject(
                            "select has_table_privilege(?, ?, 'SELECT')", Boolean.class, role, table)).isFalse();
                }
            }
        }
    }
}
