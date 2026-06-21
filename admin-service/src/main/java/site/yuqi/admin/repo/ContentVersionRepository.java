package site.yuqi.admin.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import site.yuqi.admin.domain.ContentVersion;

import java.util.Optional;
import java.util.UUID;

public interface ContentVersionRepository extends JpaRepository<ContentVersion, UUID> {
    Optional<ContentVersion> findTopBySourceTypeAndSourceIdTextOrderByVersionDesc(String sourceType, String sourceIdText);
}
