package site.yuqi.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.yuqi.admin.domain.source.ProjectSubsystem;
import site.yuqi.admin.dto.ProjectSubsystemRequest;
import site.yuqi.admin.repo.source.ProjectRepository;
import site.yuqi.admin.repo.source.ProjectSubsystemRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectSubsystemServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock ProjectSubsystemRepository repository;
    @Mock ProjectRepository projectRepository;
    @Mock AuditLogService auditLogService;

    private ProjectSubsystemService service;

    @BeforeEach
    void setUp() {
        service = new ProjectSubsystemService(repository, projectRepository, auditLogService);
    }

    @Test
    void createNormalizesMetadataAndPersistsValidatedDiagram() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(repository.save(any(ProjectSubsystem.class))).thenAnswer(call -> {
            ProjectSubsystem value = call.getArgument(0);
            value.setId(UUID.randomUUID());
            return value;
        });

        ProjectSubsystem created = service.create(projectId, request(validDiagram()), "admin@test");

        assertThat(created.getProjectId()).isEqualTo(projectId);
        assertThat(created.getSlug()).isEqualTo("agent-platform");
        assertThat(created.getMaturity()).isEqualTo("BUILT");
        assertThat(created.isActive()).isTrue();
    }

    @Test
    void rejectsRouteStepThatReferencesUnknownNode() throws Exception {
        JsonNode invalid = JSON.readTree(validDiagram().toString());
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.at("/routes/0/steps/1"))
                .put("nodeId", "missing");

        assertThatThrownBy(() -> ProjectSubsystemService.validateDiagram(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown node");
    }

    @Test
    void rejectsUnsupportedComponentShape() throws Exception {
        JsonNode invalid = JSON.readTree(validDiagram().toString());
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.at("/nodes/0"))
                .put("shape", "kafka");

        assertThatThrownBy(() -> ProjectSubsystemService.validateDiagram(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shape must be one of");
    }

    @Test
    void archiveKeepsRecordAndDisablesPublicVisibility() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID subsystemId = UUID.randomUUID();
        ProjectSubsystem existing = ProjectSubsystem.builder()
                .id(subsystemId)
                .projectId(projectId)
                .slug("agent-platform")
                .title("Agent Platform")
                .summary("Summary")
                .designIntent("Intent")
                .maturity("BUILT")
                .active(true)
                .diagramConfig(validDiagram())
                .build();
        when(repository.findByIdAndProjectId(subsystemId, projectId)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        ProjectSubsystem archived = service.archive(projectId, subsystemId, "admin@test");

        assertThat(archived.isActive()).isFalse();
    }

    private static ProjectSubsystemRequest request(JsonNode diagram) {
        ProjectSubsystemRequest request = new ProjectSubsystemRequest();
        request.setSlug(" Agent Platform ");
        request.setTitle("Agent Platform");
        request.setSummary("Grounded conversational runtime");
        request.setDesignIntent("Reliable tool execution");
        request.setMaturity("built");
        request.setDiagramConfig(diagram);
        return request;
    }

    private static JsonNode validDiagram() throws Exception {
        return JSON.readTree("""
                {
                  "defaultRoute": "request",
                  "defaultNode": "agent",
                  "nodes": [
                    {
                      "id": "client", "code": "UI", "label": "CLIENT", "title": "Client",
                      "shape": "client",
                      "x": 10, "y": 50, "responsibility": "Sends requests",
                      "data": "Messages", "reliability": "Abortable requests"
                    },
                    {
                      "id": "agent", "code": "AI", "label": "AGENT", "title": "Agent",
                      "shape": "service",
                      "x": 50, "y": 50, "responsibility": "Plans requests",
                      "data": "Planner state", "reliability": "Policy gates"
                    }
                  ],
                  "edges": [{"from": "client", "to": "agent", "label": "HTTPS", "kind": "sync"}],
                  "routes": [{
                    "key": "request", "label": "Request",
                    "steps": [
                      {"nodeId": "client", "description": "Request begins"},
                      {"nodeId": "agent", "description": "Request is planned"}
                    ]
                  }]
                }
                """);
    }
}
