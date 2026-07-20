package site.yuqi.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.domain.source.ProjectSubsystem;
import site.yuqi.admin.dto.ProjectSubsystemRequest;
import site.yuqi.admin.repo.source.ProjectRepository;
import site.yuqi.admin.repo.source.ProjectSubsystemRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectSubsystemService {
    private static final Set<String> MATURITY_VALUES = Set.of("BUILT", "SYSTEM_DESIGN");
    private static final Set<String> NODE_SHAPES = Set.of(
            "client", "service", "gateway", "worker", "database", "event-stream",
            "cache", "search-index", "external", "model", "policy"
    );
    private static final Set<String> EDGE_KINDS = Set.of("sync", "async", "data");

    private final ProjectSubsystemRepository repository;
    private final ProjectRepository projectRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<ProjectSubsystem> list(UUID projectId) {
        requireProject(projectId);
        return repository.findByProjectIdOrderBySortOrderAscCreatedAtAsc(projectId);
    }

    @Transactional
    public ProjectSubsystem create(UUID projectId, ProjectSubsystemRequest request, String actor) {
        requireProject(projectId);
        UUID linkedProjectId = request.getLinkedProjectId();
        if (linkedProjectId != null) requireProject(linkedProjectId);

        String slug = normalizeSlug(request.getSlug());
        if (repository.existsByProjectIdAndSlugIgnoreCase(projectId, slug)) {
            throw new IllegalStateException("Subsystem slug already exists for this project: " + slug);
        }
        validateDiagram(request.getDiagramConfig());

        ProjectSubsystem created = repository.save(ProjectSubsystem.builder()
                .projectId(projectId)
                .linkedProjectId(linkedProjectId)
                .slug(slug)
                .title(request.getTitle().trim())
                .eyebrow(trimToNull(request.getEyebrow()))
                .summary(request.getSummary().trim())
                .designIntent(request.getDesignIntent().trim())
                .maturity(normalizeMaturity(request.getMaturity()))
                .sortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder())
                .diagramConfig(request.getDiagramConfig())
                .active(request.getActive() == null || request.getActive())
                .build());
        auditLogService.log(actor, AuditAction.CREATE, "PROJECT_SUBSYSTEM", created.getId().toString(),
                null, null, snapshot(created));
        return created;
    }

    @Transactional
    public ProjectSubsystem update(UUID projectId, UUID subsystemId,
                                   ProjectSubsystemRequest request, String actor) {
        requireProject(projectId);
        ProjectSubsystem subsystem = getForProject(projectId, subsystemId);
        Map<String, Object> before = snapshot(subsystem);

        UUID linkedProjectId = request.getLinkedProjectId();
        if (linkedProjectId != null) requireProject(linkedProjectId);
        String slug = normalizeSlug(request.getSlug());
        if (repository.existsByProjectIdAndSlugIgnoreCaseAndIdNot(projectId, slug, subsystemId)) {
            throw new IllegalStateException("Subsystem slug already exists for this project: " + slug);
        }
        validateDiagram(request.getDiagramConfig());

        subsystem.setLinkedProjectId(linkedProjectId);
        subsystem.setSlug(slug);
        subsystem.setTitle(request.getTitle().trim());
        subsystem.setEyebrow(trimToNull(request.getEyebrow()));
        subsystem.setSummary(request.getSummary().trim());
        subsystem.setDesignIntent(request.getDesignIntent().trim());
        subsystem.setMaturity(normalizeMaturity(request.getMaturity()));
        subsystem.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        subsystem.setDiagramConfig(request.getDiagramConfig());
        subsystem.setActive(request.getActive() == null || request.getActive());

        ProjectSubsystem updated = repository.save(subsystem);
        auditLogService.log(actor, AuditAction.UPDATE, "PROJECT_SUBSYSTEM", subsystemId.toString(),
                null, before, snapshot(updated));
        return updated;
    }

    @Transactional
    public ProjectSubsystem archive(UUID projectId, UUID subsystemId, String actor) {
        ProjectSubsystem subsystem = getForProject(projectId, subsystemId);
        Map<String, Object> before = snapshot(subsystem);
        subsystem.setActive(false);
        ProjectSubsystem archived = repository.save(subsystem);
        auditLogService.log(actor, AuditAction.ARCHIVE, "PROJECT_SUBSYSTEM", subsystemId.toString(),
                null, before, snapshot(archived));
        return archived;
    }

    private ProjectSubsystem getForProject(UUID projectId, UUID subsystemId) {
        return repository.findByIdAndProjectId(subsystemId, projectId)
                .orElseThrow(() -> new NoSuchElementException("Project subsystem not found: " + subsystemId));
    }

    private void requireProject(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new NoSuchElementException("PROJECT not found: " + projectId);
        }
    }

    private static String normalizeSlug(String raw) {
        String slug = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.isBlank() || slug.length() > 80) {
            throw new IllegalArgumentException("Subsystem slug must contain letters or numbers and be at most 80 characters");
        }
        return slug;
    }

    private static String normalizeMaturity(String raw) {
        String maturity = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!MATURITY_VALUES.contains(maturity)) {
            throw new IllegalArgumentException("Subsystem maturity must be BUILT or SYSTEM_DESIGN");
        }
        return maturity;
    }

    static void validateDiagram(JsonNode config) {
        if (config == null || !config.isObject()) {
            throw new IllegalArgumentException("diagramConfig must be a JSON object");
        }
        JsonNode nodes = config.get("nodes");
        if (nodes == null || !nodes.isArray() || nodes.size() < 2 || nodes.size() > 24) {
            throw new IllegalArgumentException("diagramConfig.nodes must contain 2 to 24 nodes");
        }

        Set<String> nodeIds = new HashSet<>();
        for (JsonNode node : nodes) {
            String id = requiredText(node, "id", 48);
            requiredText(node, "code", 12);
            requiredText(node, "label", 80);
            requiredText(node, "title", 160);
            requireAllowedValue(node, "shape", NODE_SHAPES);
            requiredText(node, "responsibility", 500);
            requiredText(node, "data", 500);
            requiredText(node, "reliability", 500);
            boundedCoordinate(node, "x");
            boundedCoordinate(node, "y");
            if (!nodeIds.add(id)) {
                throw new IllegalArgumentException("diagramConfig contains duplicate node id: " + id);
            }
        }

        JsonNode edges = config.get("edges");
        if (edges == null || !edges.isArray() || edges.size() > 64) {
            throw new IllegalArgumentException("diagramConfig.edges must be an array with at most 64 edges");
        }
        for (JsonNode edge : edges) {
            requireNodeReference(edge, "from", nodeIds);
            requireNodeReference(edge, "to", nodeIds);
            requiredText(edge, "label", 48);
            requireAllowedValue(edge, "kind", EDGE_KINDS);
        }

        JsonNode routes = config.get("routes");
        if (routes == null || !routes.isArray() || routes.isEmpty() || routes.size() > 8) {
            throw new IllegalArgumentException("diagramConfig.routes must contain 1 to 8 routes");
        }
        Set<String> routeKeys = new HashSet<>();
        for (JsonNode route : routes) {
            String key = requiredText(route, "key", 48);
            requiredText(route, "label", 48);
            if (!routeKeys.add(key)) {
                throw new IllegalArgumentException("diagramConfig contains duplicate route key: " + key);
            }
            JsonNode steps = route.get("steps");
            if (steps == null || !steps.isArray() || steps.size() < 2 || steps.size() > 40) {
                throw new IllegalArgumentException("Each route must contain 2 to 40 steps");
            }
            for (JsonNode step : steps) {
                requireNodeReference(step, "nodeId", nodeIds);
                requiredText(step, "description", 300);
            }
        }

        String defaultRoute = requiredText(config, "defaultRoute", 48);
        if (!routeKeys.contains(defaultRoute)) {
            throw new IllegalArgumentException("diagramConfig.defaultRoute must reference a route key");
        }
        String defaultNode = requiredText(config, "defaultNode", 48);
        if (!nodeIds.contains(defaultNode)) {
            throw new IllegalArgumentException("diagramConfig.defaultNode must reference a node id");
        }
    }

    private static void requireNodeReference(JsonNode object, String field, Set<String> nodeIds) {
        String value = requiredText(object, field, 48);
        if (!nodeIds.contains(value)) {
            throw new IllegalArgumentException("diagramConfig." + field + " references unknown node: " + value);
        }
    }

    private static String requiredText(JsonNode object, String field, int maxLength) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            throw new IllegalArgumentException("diagramConfig." + field + " must be non-empty text up to " + maxLength + " characters");
        }
        return value.asText();
    }

    private static void requireAllowedValue(JsonNode object, String field, Set<String> allowed) {
        String value = requiredText(object, field, 48);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(
                    "diagramConfig." + field + " must be one of: " + String.join(", ", allowed));
        }
    }

    private static void boundedCoordinate(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || value.asDouble() < 4 || value.asDouble() > 96) {
            throw new IllegalArgumentException("diagramConfig node " + field + " must be between 4 and 96");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static Map<String, Object> snapshot(ProjectSubsystem value) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("projectId", value.getProjectId());
        snapshot.put("linkedProjectId", value.getLinkedProjectId());
        snapshot.put("slug", value.getSlug());
        snapshot.put("title", value.getTitle());
        snapshot.put("eyebrow", value.getEyebrow());
        snapshot.put("summary", value.getSummary());
        snapshot.put("designIntent", value.getDesignIntent());
        snapshot.put("maturity", value.getMaturity());
        snapshot.put("sortOrder", value.getSortOrder());
        snapshot.put("active", value.isActive());
        snapshot.put("diagramConfig", value.getDiagramConfig());
        return snapshot;
    }
}
