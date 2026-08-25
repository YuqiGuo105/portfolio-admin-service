package site.yuqi.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.yuqi.admin.domain.AdminUser;
import site.yuqi.admin.domain.AdminUserRole;
import site.yuqi.admin.domain.AdminUserStatus;
import site.yuqi.admin.repo.AdminUserRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock AdminUserRepository repository;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(repository, "owner@example.com");
    }

    @Test
    void authorizeUsesActiveDatabaseRoleBeforeFallback() {
        when(repository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(AdminUser.builder()
                .email("admin@example.com")
                .role(AdminUserRole.PUBLISHER)
                .status(AdminUserStatus.ACTIVE)
                .build()));

        AdminUserService.Authorization authorization =
                service.authorize(" Admin@Example.com ", List.of("admin@example.com"));

        assertThat(authorization.allowed()).isTrue();
        assertThat(authorization.role()).isEqualTo(AdminUserRole.PUBLISHER);
        assertThat(authorization.source()).isEqualTo("admin_users");
        assertThat(authorization.owner()).isFalse();
    }

    @Test
    void authorizeDeniesSuspendedDatabaseUserEvenWhenFallbackAllowsEmail() {
        when(repository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(AdminUser.builder()
                .email("admin@example.com")
                .role(AdminUserRole.ADMIN)
                .status(AdminUserStatus.SUSPENDED)
                .build()));

        AdminUserService.Authorization authorization =
                service.authorize("admin@example.com", List.of("admin@example.com"));

        assertThat(authorization.allowed()).isFalse();
    }

    @Test
    void authorizeFallsBackToEnvironmentAllowlistWhenNoDatabaseRowExists() {
        when(repository.findByEmailIgnoreCase("fallback@example.com")).thenReturn(Optional.empty());

        AdminUserService.Authorization authorization =
                service.authorize("fallback@example.com", List.of("FALLBACK@example.com"));

        assertThat(authorization.allowed()).isTrue();
        assertThat(authorization.role()).isEqualTo(AdminUserRole.ADMIN);
        assertThat(authorization.source()).isEqualTo("env_allowlist");
        assertThat(authorization.owner()).isFalse();
    }

    @Test
    void authorizeDeniesOrdinarySignedInUserWithoutAdminRegistration() {
        when(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty());

        AdminUserService.Authorization authorization =
                service.authorize("user@example.com", List.of());

        assertThat(authorization.allowed()).isFalse();
        assertThat(authorization.role()).isNull();
        assertThat(authorization.owner()).isFalse();
    }

    @Test
    void configuredOwnerIsAlwaysAuthorizedAsAdmin() {
        AdminUserService.Authorization authorization =
                service.authorize(" OWNER@example.com ", List.of());

        assertThat(authorization.allowed()).isTrue();
        assertThat(authorization.role()).isEqualTo(AdminUserRole.ADMIN);
        assertThat(authorization.source()).isEqualTo("owner_policy");
        assertThat(authorization.owner()).isTrue();
    }

    @Test
    void configuredOwnerCannotBeDemotedOrSuspended() {
        assertThatThrownBy(() -> service.upsert(
                "owner@example.com", "EDITOR", "ACTIVE", null, null, "owner@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active ADMIN");

        AdminUser owner = AdminUser.builder()
                .email("owner@example.com")
                .role(AdminUserRole.ADMIN)
                .status(AdminUserStatus.ACTIVE)
                .build();
        when(repository.findById(any())).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.updateStatus(
                java.util.UUID.randomUUID(), "SUSPENDED", "test", "owner@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active ADMIN");
    }

    @Test
    void upsertNormalizesEmailAndRejectsInvalidRole() {
        assertThatThrownBy(() -> service.upsert(
                "editor@example.com", "superuser", "active", null, null, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid admin role");

        when(repository.findByEmailIgnoreCase("editor@example.com")).thenReturn(Optional.empty());
        when(repository.save(any(AdminUser.class))).thenAnswer(call -> call.getArgument(0));

        AdminUser saved = service.upsert(
                " Editor@Example.com ", "editor", "active", "Editor", "trusted operator", "tester");

        assertThat(saved.getEmail()).isEqualTo("editor@example.com");
        assertThat(saved.getRole()).isEqualTo(AdminUserRole.EDITOR);
        assertThat(saved.getStatus()).isEqualTo(AdminUserStatus.ACTIVE);
        assertThat(saved.getUpdatedBy()).isEqualTo("tester");
    }
}
