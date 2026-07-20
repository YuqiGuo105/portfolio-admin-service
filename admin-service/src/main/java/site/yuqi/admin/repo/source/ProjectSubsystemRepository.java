package site.yuqi.admin.repo.source;

import org.springframework.data.jpa.repository.JpaRepository;
import site.yuqi.admin.domain.source.ProjectSubsystem;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectSubsystemRepository extends JpaRepository<ProjectSubsystem, UUID> {
    List<ProjectSubsystem> findByProjectIdOrderBySortOrderAscCreatedAtAsc(UUID projectId);

    Optional<ProjectSubsystem> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByProjectIdAndSlugIgnoreCase(UUID projectId, String slug);

    boolean existsByProjectIdAndSlugIgnoreCaseAndIdNot(UUID projectId, String slug, UUID id);
}
