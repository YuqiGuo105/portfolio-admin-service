package site.yuqi.admin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.yuqi.admin.domain.ContentVersion;
import site.yuqi.admin.repo.ContentVersionRepository;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VersionServiceTest {
    @Mock ContentVersionRepository repository;

    @Test
    void diffReportsOnlyChangedFields() {
        ContentVersion before = ContentVersion.builder()
                .sourceType("BLOG").sourceIdText("article-1").version(1)
                .snapshot(Map.of("title", "Old title", "content", "Same body"))
                .build();
        ContentVersion after = ContentVersion.builder()
                .sourceType("BLOG").sourceIdText("article-1").version(2)
                .snapshot(Map.of("title", "Correct title", "content", "Same body"))
                .build();
        when(repository.findBySourceTypeAndSourceIdTextAndVersion("BLOG", "article-1", 1))
                .thenReturn(Optional.of(before));
        when(repository.findBySourceTypeAndSourceIdTextAndVersion("BLOG", "article-1", 2))
                .thenReturn(Optional.of(after));

        Map<String, Object> diff = new VersionService(repository)
                .diff("BLOG", "article-1", 1, 2);

        assertThat(diff).containsEntry("changedFieldCount", 1);
        Map<?, ?> changes = (Map<?, ?>) diff.get("changes");
        assertThat(changes).hasSize(1);
        assertThat(changes.containsKey("title")).isTrue();
    }
}
