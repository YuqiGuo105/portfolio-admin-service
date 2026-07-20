package site.yuqi.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.domain.source.ProjectSubsystem;
import site.yuqi.admin.dto.ProjectSubsystemRequest;
import site.yuqi.admin.security.AdminPrincipal;
import site.yuqi.admin.service.ProjectSubsystemService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/projects/{projectId}/subsystems")
@RequiredArgsConstructor
@Tag(name = "Project Subsystems", description = "Database-owned interactive architecture diagrams for parent projects.")
public class ProjectSubsystemAdminController {
    private final ProjectSubsystemService service;

    @GetMapping
    @Operation(summary = "List all subsystem diagrams, including archived records")
    public ResponseEntity<Map<String, List<ProjectSubsystem>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(Map.of("items", service.list(projectId)));
    }

    @PostMapping
    @Operation(summary = "Create a validated subsystem diagram")
    public ResponseEntity<ProjectSubsystem> create(@PathVariable UUID projectId,
                                                   @Valid @RequestBody ProjectSubsystemRequest body,
                                                   HttpServletRequest request) {
        return ResponseEntity.ok(service.create(projectId, body, AdminPrincipal.from(request)));
    }

    @PutMapping("/{subsystemId}")
    @Operation(summary = "Update subsystem metadata and diagram configuration")
    public ResponseEntity<ProjectSubsystem> update(@PathVariable UUID projectId,
                                                   @PathVariable UUID subsystemId,
                                                   @Valid @RequestBody ProjectSubsystemRequest body,
                                                   HttpServletRequest request) {
        return ResponseEntity.ok(service.update(projectId, subsystemId, body, AdminPrincipal.from(request)));
    }

    @DeleteMapping("/{subsystemId}")
    @Operation(summary = "Archive a subsystem without deleting its record")
    public ResponseEntity<ProjectSubsystem> archive(@PathVariable UUID projectId,
                                                    @PathVariable UUID subsystemId,
                                                    HttpServletRequest request) {
        return ResponseEntity.ok(service.archive(projectId, subsystemId, AdminPrincipal.from(request)));
    }
}
