package site.yuqi.admin.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import site.yuqi.admin.domain.ContentVersion;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ContentVersionRepository extends JpaRepository<ContentVersion, UUID> {
    Optional<ContentVersion> findTopBySourceTypeAndSourceIdTextOrderByVersionDesc(String sourceType, String sourceIdText);
    List<ContentVersion> findBySourceTypeAndSourceIdTextOrderByVersionDesc(
            String sourceType, String sourceIdText, Pageable pageable);
    Optional<ContentVersion> findBySourceTypeAndSourceIdTextAndVersion(
            String sourceType, String sourceIdText, int version);
}
