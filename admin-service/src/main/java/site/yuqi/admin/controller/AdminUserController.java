package site.yuqi.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.admin.domain.AdminUser;
import site.yuqi.admin.domain.AdminUserRole;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.dto.AdminUserRequest;
import site.yuqi.admin.dto.AdminUserStatusRequest;
import site.yuqi.admin.security.AdminPrincipal;
import site.yuqi.admin.service.AdminUserService;
import site.yuqi.admin.service.AuditLogService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin Users", description = "Manage admin access and roles.")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final AuditLogService auditLogService;

    @GetMapping("/me")
    @Operation(summary = "Return the current admin principal")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        boolean owner = AdminPrincipal.isOwner(request);
        return ResponseEntity.ok(Map.of(
                "email", AdminPrincipal.from(request),
                "role", AdminPrincipal.roleFrom(request),
                "owner", owner,
                "permissions", permissions(request, owner),
                "authSource", String.valueOf(request.getAttribute(AdminPrincipal.AUTH_SOURCE_ATTR))));
    }

    @GetMapping
    @Operation(summary = "List admin users")
    public ResponseEntity<?> list(@RequestParam(value = "status", required = false) String status,
                                  @RequestParam(value = "role", required = false) String role,
                                  @RequestParam(value = "limit", defaultValue = "50") int limit,
                                  @RequestParam(value = "offset", defaultValue = "0") int offset,
                                  HttpServletRequest request) {
        if (!AdminPrincipal.isOwner(request)) return forbidden();
        return ResponseEntity.ok(Map.of("items", adminUserService.list(status, role, limit, offset)));
    }

    @PostMapping
    @Operation(summary = "Create or update an admin user by email")
    public ResponseEntity<?> upsert(@RequestBody AdminUserRequest body,
                                    HttpServletRequest request) {
        if (!AdminPrincipal.isOwner(request)) return forbidden();
        String actor = AdminPrincipal.from(request);
        AdminUser user = adminUserService.upsert(
                body.email(), body.role(), body.status(), body.displayName(), body.note(), actor);
        auditLogService.log(actor, AuditAction.ADMIN_USER_UPSERT, "ADMIN_USER", user.getId().toString(),
                null, null, Map.of(
                        "email", user.getEmail(),
                        "role", user.getRole().name(),
                        "status", user.getStatus().name()));
        return ResponseEntity.ok(user);
    }

    @PatchMapping("/{userId}/status")
    @Operation(summary = "Update an admin user's status")
    public ResponseEntity<?> updateStatus(@PathVariable UUID userId,
                                          @RequestBody AdminUserStatusRequest body,
                                          HttpServletRequest request) {
        if (!AdminPrincipal.isOwner(request)) return forbidden();
        String actor = AdminPrincipal.from(request);
        AdminUser user = adminUserService.updateStatus(userId, body.status(), body.note(), actor);
        auditLogService.log(actor, AuditAction.ADMIN_USER_STATUS_UPDATE, "ADMIN_USER", user.getId().toString(),
                null, null, Map.of("email", user.getEmail(), "status", user.getStatus().name()));
        return ResponseEntity.ok(user);
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", "Owner permission required."));
    }

    private static java.util.List<String> permissions(HttpServletRequest request, boolean owner) {
        AdminUserRole role;
        try {
            role = AdminUserRole.valueOf(AdminPrincipal.roleFrom(request).toUpperCase());
        } catch (Exception ignored) {
            role = AdminUserRole.EDITOR;
        }
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        permissions.add("admin.read");
        if (role.ordinal() >= AdminUserRole.EDITOR.ordinal()) permissions.add("content.write");
        if (role.ordinal() >= AdminUserRole.PUBLISHER.ordinal()) permissions.add("content.publish");
        if (role.ordinal() >= AdminUserRole.ADMIN.ordinal()) permissions.add("operations.manage");
        if (owner) permissions.add("admin.users.manage");
        return java.util.List.copyOf(permissions);
    }
}
