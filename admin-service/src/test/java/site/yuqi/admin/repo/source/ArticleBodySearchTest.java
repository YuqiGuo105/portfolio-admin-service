package site.yuqi.admin.repo.source;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import site.yuqi.admin.domain.source.Blog;
import site.yuqi.admin.domain.source.LifeBlog;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class ArticleBodySearchTest {
    @Autowired TestEntityManager entities;
    @Autowired BlogRepository blogs;
    @Autowired LifeBlogRepository life;

    @Test
    void searchesChineseBodyAndRetainsCategoryFilter() {
        var article = entities.persistAndFlush(LifeBlog.builder().title("New Grad Offer")
                .description("Career journal").category("Career").content("面试经历与求职记录").build());
        assertThat(life.search("面试", null, PageRequest.of(0, 10)))
                .extracting(LifeBlog::getId).contains(article.getId());
        assertThat(life.search("面试", "Travel", PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void technicalArticleBodySearchIsCaseInsensitive() {
        var article = entities.persistAndFlush(Blog.builder().title("Engineering notes")
                .description("A journal").content("An INTERVIEW experience").build());
        assertThat(blogs.search("interview", null, PageRequest.of(0, 10)))
                .extracting(Blog::getId).contains(article.getId());
        assertThat(blogs.search("unmatched-keyword", null, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void discoversAliasesInTagsWithoutChangingCategoryBoundaries() {
        var travel = entities.persistAndFlush(LifeBlog.builder().title("Road trip")
                .description("A journey").category("Travel").tags("盐湖城,Salt Lake City,SLC").build());
        var blog = entities.persistAndFlush(Blog.builder().title("Engineering notes")
                .description("Versioning").category("Engineering").tags("版本控制,GitHub").build());
        assertThat(life.search("盐湖城", "Travel", PageRequest.of(0, 10)))
                .extracting(LifeBlog::getId).contains(travel.getId());
        assertThat(life.search("SLC", "Career", PageRequest.of(0, 10))).isEmpty();
        assertThat(blogs.search("版本控制", "Engineering", PageRequest.of(0, 10)))
                .extracting(Blog::getId).contains(blog.getId());
    }
}
