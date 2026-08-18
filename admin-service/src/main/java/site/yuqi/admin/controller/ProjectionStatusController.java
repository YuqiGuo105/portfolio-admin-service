package site.yuqi.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.dto.ProjectionStatusResponse;
import site.yuqi.admin.service.ProjectionStatusService;

@RestController
@RequestMapping("/api/admin/projections")
@RequiredArgsConstructor
public class ProjectionStatusController {

    private final ProjectionStatusService service;

    @GetMapping("/{sourceType}/{sourceId}")
    public ResponseEntity<ProjectionStatusResponse> status(
            @PathVariable("sourceType") String sourceType,
            @PathVariable("sourceId") String sourceId) {
        return ResponseEntity.ok(service.status(SourceType.valueOf(sourceType.toUpperCase()), sourceId));
    }
}
