package site.yuqi.admin.mediapipeline;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ArticleMediaMarkupTest {
    final UUID id = UUID.randomUUID();
    final String body = "<h2>Workflow</h2>\n<pre class='mermaid'>A --&gt; B &amp; C</pre><p>Original &#x21;</p>";

    @Test void attachesAndDetachesWithoutRewritingArticleOrMermaid() {
        for (String position : new String[]{"START", "END"}) {
            String result = ArticleMediaMarkup.attach(body, id, position);
            assertThat(ArticleMediaMarkup.ids(result)).containsExactly(id);
            assertThat(result).contains(body);
            assertThat(ArticleMediaMarkup.attach(result, id, position)).isEqualTo(result);
            assertThat(ArticleMediaMarkup.detach(result, id)).isEqualTo(body);
        }
    }

    @Test void hashChangesWithAnyBodyEditAndIsStableForNull() {
        assertThat(ArticleMediaMarkup.hash(body)).hasSize(64).isNotEqualTo(ArticleMediaMarkup.hash(body + " "));
        assertThat(ArticleMediaMarkup.hash(null)).isEqualTo(ArticleMediaMarkup.hash(""));
    }

    @Test void malformedExcessiveOrOverlappingReferencesAreRejected() {
        assertThatThrownBy(() -> ArticleMediaMarkup.ids("<figure data-media-id='bad'></figure>"))
                .isInstanceOf(IllegalArgumentException.class);
        String figure = "<figure data-media-id='" + id + "'>";
        assertThatThrownBy(() -> ArticleMediaMarkup.detach(figure, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArticleMediaMarkup.detach(figure + figure + "</figure></figure>", id))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArticleMediaMarkup.ids((figure + "</figure>").repeat(51)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArticleMediaMarkup.attach(body, id, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void removesMultipleReferencesAndLeavesOtherAssetsIntact() {
        UUID other = UUID.randomUUID();
        String withOther = ArticleMediaMarkup.attach(body, other, "END");
        String figure = "<figure data-media-id='" + id + "'></figure>";
        assertThat(ArticleMediaMarkup.detach(figure + withOther + figure, id)).isEqualTo(withOther);
        assertThat(ArticleMediaMarkup.detach(body, id)).isEqualTo(body);
    }
}
