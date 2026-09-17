package site.yuqi.admin.controller;

import org.junit.jupiter.api.Test;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.dto.ContentListItemDto;
import site.yuqi.admin.service.ContentService;
import site.yuqi.admin.security.AdminAuthFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SearchProjectionControllerTest {
    @Test
    void projectionHttpRouteRequiresAuthenticationAndSerializesTheNestedContract() throws Exception {
        ContentService content = mock(ContentService.class);
        when(content.listAll(SourceType.LIFE_BLOG, null, null, 50, 0)).thenReturn(List.of(
                NormalizedContent.builder().sourceType(SourceType.LIFE_BLOG).sourceId("2")
                        .title("Travel").content("Salt Lake City").raw(Map.of("require_login", true)).build()));
        var controller = new ContentAdminController(content, null, null, null, null, null);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new AdminAuthFilter("test-secret", "", "", null)).build();
        mvc.perform(get("/api/admin/content/search-projection").param("type", "LIFE_BLOG"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(content);
        mvc.perform(get("/api/admin/content/search-projection").param("type", "LIFE_BLOG")
                        .header("X-Admin-Secret", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.items[0].search.schemaVersion").value(1))
                .andExpect(jsonPath("$.items[0].search.text").value("Salt Lake City"))
                .andExpect(jsonPath("$.items[0].search.requiresLogin").value(true));
    }

    @Test
    void batchesSearchBodiesWithoutPerRecordVersionOrJobQueries() {
        ContentService content = mock(ContentService.class);
        when(content.listAll(SourceType.LIFE_BLOG, null, null, 50, 0)).thenReturn(List.of(
                NormalizedContent.builder().sourceType(SourceType.LIFE_BLOG).sourceId("2")
                        .title("Travel").content("<p>Salt Lake City</p>").raw(Map.of("require_login", true)).build()));
        var controller = new ContentAdminController(content, null, null, null, null, null);
        var result = controller.searchProjection("LIFE_BLOG", 500).getBody();
        assertThat(result).containsEntry("schemaVersion", 1);
        var items = (List<?>) result.get("items");
        var item = (ContentListItemDto) items.getFirst();
        assertThat(item.getSearch().text()).isEqualTo("Salt Lake City");
        assertThat(item.getSearch().requiresLogin()).isTrue();
        verify(content).listAll(SourceType.LIFE_BLOG, null, null, 50, 0);
        verifyNoMoreInteractions(content);
    }
}
