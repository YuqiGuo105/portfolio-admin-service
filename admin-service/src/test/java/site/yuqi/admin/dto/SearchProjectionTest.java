package site.yuqi.admin.dto;

import org.junit.jupiter.api.Test;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.SourceType;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SearchProjectionTest {
    @Test void regularContentListsDoNotIncludeInternalSearchBodies() {
        var content = NormalizedContent.builder().sourceType(SourceType.LIFE_BLOG)
                .sourceId("2").title("Travel").content("Restricted original").build();
        assertThat(ContentListItemDto.fromNormalized(content).getSearch()).isNull();
    }

    @Test void batchesPlainSearchTextAndPreservesRestrictedAccess() {
        var content = NormalizedContent.builder().sourceType(SourceType.LIFE_BLOG)
                .content("<h2>Salt Lake City</h2><script>secret()</script><p>Trip &amp; photos</p>")
                .raw(Map.of("require_login", true)).build();
        var result = SearchProjection.from(content);
        assertThat(result.text()).isEqualTo("Salt Lake City Trip & photos");
        assertThat(result.requiresLogin()).isTrue();
        assertThat(result.truncated()).isFalse();
    }

    @Test void unknownLifeAccessFailsClosedAndLongBodiesAreExplicitlyTruncated() {
        var content = NormalizedContent.builder().sourceType(SourceType.LIFE_BLOG).content("x".repeat(40001)).build();
        assertThat(SearchProjection.from(content).requiresLogin()).isTrue();
        assertThat(SearchProjection.from(content).text()).hasSize(40000);
        assertThat(SearchProjection.from(content).truncated()).isTrue();
    }

    @Test void explicitPublicArticleRemainsPublic() {
        var content = NormalizedContent.builder().sourceType(SourceType.LIFE_BLOG).raw(Map.of("require_login", false)).build();
        assertThat(SearchProjection.from(content).requiresLogin()).isFalse();
        assertThat(SearchProjection.from(content).text()).isEmpty();
    }
}
