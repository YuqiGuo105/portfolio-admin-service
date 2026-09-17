package site.yuqi.searchindexer.source;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentFetcherTest {
    @SuppressWarnings("unchecked")
    @Test void lifeBlogProjectionIncludesBodyCanonicalFieldsAndLoginFlag() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(2L);
        when(rs.getString("content")).thenReturn("<p>Salt Lake City</p>");
        when(rs.getString("tags")).thenReturn("Travel, SLC");
        when(rs.getObject("require_login")).thenReturn(true);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(2L))).thenAnswer(call -> {
            assertThat((String) call.getArgument(0)).contains("content", "require_login");
            return ((RowMapper<Map<String, Object>>) call.getArgument(1)).mapRow(rs, 0);
        });
        Map<String, Object> doc = new ContentFetcher(jdbc).fetchSearchDocument("LIFE_BLOG", "2").orElseThrow();
        assertThat(doc).containsEntry("source_type", "LIFE_BLOG").containsEntry("source_id", "2")
                .containsEntry("content", "Salt Lake City").containsEntry("body", "Salt Lake City")
                .containsEntry("requires_login", true).containsEntry("url", "/life-blog/2");
    }
}
