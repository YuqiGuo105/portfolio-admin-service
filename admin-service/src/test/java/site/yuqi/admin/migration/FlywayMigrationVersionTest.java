package site.yuqi.admin.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FlywayMigrationVersionTest {

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V([^_]+)__.+\\.sql$");

    @Test
    void versionedMigrationsHaveUniqueVersions() throws IOException, URISyntaxException {
        URI migrationUri =
                getClass().getClassLoader().getResource("db/migration").toURI();

        try (var files = Files.list(Path.of(migrationUri))) {
            Map<String, List<String>> filesByVersion =
                    files.map(path -> path.getFileName().toString())
                            .map(VERSIONED_MIGRATION::matcher)
                            .filter(Matcher::matches)
                            .collect(
                                    Collectors.groupingBy(
                                            matcher -> matcher.group(1),
                                            Collectors.mapping(Matcher::group, Collectors.toList())));

            assertThat(filesByVersion)
                    .as("Flyway versions must be unique: %s", filesByVersion)
                    .allSatisfy((version, names) -> assertThat(names).hasSize(1));
        }
    }
}
