package site.yuqi.admin.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import site.yuqi.admin.domain.AdminUserRole;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthFilterPolicyTest {

    @Test
    void onlyOwnerCanManageAdministratorIdentities() {
        MockHttpServletRequest request = request("GET", "/api/admin/users");

        assertThat(AdminAuthFilter.authorizeRequest(request, AdminUserRole.ADMIN, false)).isFalse();
        assertThat(AdminAuthFilter.authorizeRequest(request, AdminUserRole.ADMIN, true)).isTrue();
    }

    @Test
    void editorCanReadButCannotMutateOperationalResources() {
        assertThat(AdminAuthFilter.authorizeRequest(
                request("GET", "/api/admin/content"), AdminUserRole.EDITOR, false)).isTrue();
        assertThat(AdminAuthFilter.authorizeRequest(
                request("POST", "/api/admin/outbox-events/123/replay"), AdminUserRole.EDITOR, false)).isFalse();
    }

    @Test
    void editorCanWriteDraftsButPublisherIsRequiredToPublish() {
        assertThat(AdminAuthFilter.authorizeRequest(
                request("POST", "/api/admin/content"), AdminUserRole.EDITOR, false)).isTrue();
        assertThat(AdminAuthFilter.authorizeRequest(
                request("POST", "/api/admin/content/123/publish"), AdminUserRole.EDITOR, false)).isFalse();
        assertThat(AdminAuthFilter.authorizeRequest(
                request("POST", "/api/admin/content/123/publish"), AdminUserRole.PUBLISHER, false)).isTrue();
    }

    @Test
    void internalAdminCredentialCannotManageOwners() {
        assertThat(AdminAuthFilter.authorizeRequest(
                request("POST", "/api/admin/users"), AdminUserRole.ADMIN, false)).isFalse();
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
