package site.yuqi.admin.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.yuqi.admin.domain.source.Project;
import site.yuqi.admin.repo.source.ProjectRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAdapterTest {

    @Mock
    private ProjectRepository repository;

    private ProjectAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ProjectAdapter(repository);
    }

    @Test
    void createDraftKeepsPublicationTimestampEmptyAndSummaryBounded() {
        when(repository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var created = adapter.create(Map.of(
                "title", "Content Intelligence Platform",
                "summary", "Short notification-safe summary",
                "content", "<h1>Full article</h1><p>Detailed body</p>"));

        assertThat(created.rawOrEmpty().get("published_at")).isNull();
        assertThat(created.rawOrEmpty().get("publication_status")).isEqualTo("DRAFT");
        assertThat(created.rawOrEmpty().get("cover_variant")).isEqualTo("IMAGE");
        assertThat(created.getSummary()).isEqualTo("Short notification-safe summary");
        assertThat(created.getContent()).contains("Detailed body");
    }

    @Test
    void markPublishedSetsTimestampWithoutReplacingSummary() {
        when(repository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UUID id = UUID.randomUUID();
        Project project = Project.builder()
                .id(id)
                .title("Visitor Behavior Analytics")
                .summary("Aggregator and sessionization case study")
                .content("<p>Full content</p>")
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(project));

        var published = adapter.markPublished(id.toString());

        assertThat(published.rawOrEmpty().get("published_at")).isNotNull();
        assertThat(published.rawOrEmpty().get("publication_status")).isEqualTo("PUBLISHED");
        assertThat(published.getSummary()).isEqualTo("Aggregator and sessionization case study");
    }

    @Test
    void legacyProjectGetsSafeExcerptInsteadOfFullHtmlAsSummary() {
        Project project = Project.builder()
                .id(UUID.randomUUID())
                .title("Legacy")
                .content("<h1>Architecture</h1><p>" + "detail ".repeat(80) + "</p>")
                .build();

        when(repository.findById(project.getId())).thenReturn(Optional.of(project));
        var normalized = adapter.get(project.getId().toString()).orElseThrow();

        assertThat(normalized.getSummary()).hasSize(280).endsWith("...");
        assertThat(normalized.getSummary()).doesNotContain("<h1>");
    }
}
