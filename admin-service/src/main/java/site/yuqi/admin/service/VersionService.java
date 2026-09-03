package site.yuqi.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.ContentVersion;
import site.yuqi.admin.repo.ContentVersionRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VersionService {

    private final ContentVersionRepository repository;

    @Transactional(readOnly = true)
    public int nextVersionFor(String sourceType, String sourceIdText) {
        return repository
                .findTopBySourceTypeAndSourceIdTextOrderByVersionDesc(sourceType, sourceIdText)
                .map(v -> v.getVersion() + 1)
                .orElse(1);
    }

    @Transactional(readOnly = true)
    public int latestVersionFor(String sourceType, String sourceIdText) {
        return repository
                .findTopBySourceTypeAndSourceIdTextOrderByVersionDesc(sourceType, sourceIdText)
                .map(ContentVersion::getVersion)
                .orElse(0);
    }

    /**
     * Snapshot the content under a new monotonic version number. Caller is
     * responsible for resolving the {@code version} (use {@link #nextVersionFor}).
     */
    @Transactional
    public ContentVersion snapshot(NormalizedContent content, int version,
                                   String actor, String changeNote,
                                   Map<String, Object> snapshotPayload) {

        Map<String, Object> snapshot = snapshotPayload != null
                ? new HashMap<>(snapshotPayload)
                : new HashMap<>();

        ContentVersion row = ContentVersion.builder()
                .sourceType(content.getSourceType().name())
                .sourceIdText(content.getSourceId())
                .version(version)
                .title(content.getTitle())
                .summary(content.getSummary())
                .content(content.getContent())
                .category(content.getCategory())
                .tags(content.getTags() == null ? null : String.join(", ", content.getTags()))
                .snapshot(snapshot)
                .changeNote(changeNote)
                .createdBy(actor)
                .build();

        return repository.save(row);
    }

    @Transactional(readOnly = true)
    public List<ContentVersion> listVersions(String sourceType, String sourceIdText, int limit) {
        return repository.findBySourceTypeAndSourceIdTextOrderByVersionDesc(
                sourceType, sourceIdText, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    @Transactional(readOnly = true)
    public ContentVersion getVersion(String sourceType, String sourceIdText, int version) {
        return repository.findBySourceTypeAndSourceIdTextAndVersion(sourceType, sourceIdText, version)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Version not found: " + sourceType + ":" + sourceIdText + ":v" + version));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> diff(String sourceType, String sourceIdText, int fromVersion, int toVersion) {
        ContentVersion from = getVersion(sourceType, sourceIdText, fromVersion);
        ContentVersion to = getVersion(sourceType, sourceIdText, toVersion);
        Map<String, Object> before = from.getSnapshot() == null ? Map.of() : from.getSnapshot();
        Map<String, Object> after = to.getSnapshot() == null ? Map.of() : to.getSnapshot();
        Map<String, Object> changed = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            Object oldValue = before.get(key);
            Object newValue = after.get(key);
            if (!java.util.Objects.deepEquals(oldValue, newValue)) {
                changed.put(key, Map.of("before", oldValue == null ? "" : oldValue,
                        "after", newValue == null ? "" : newValue));
            }
        }
        return Map.of("sourceType", sourceType, "sourceId", sourceIdText,
                "fromVersion", fromVersion, "toVersion", toVersion,
                "changedFieldCount", changed.size(), "changes", changed);
    }
}
