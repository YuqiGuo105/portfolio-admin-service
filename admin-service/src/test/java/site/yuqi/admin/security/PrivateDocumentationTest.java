package site.yuqi.admin.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import site.yuqi.admin.domain.AdminUserRole;
import site.yuqi.admin.service.AdminUserService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PrivateDocumentationTest {
    @ParameterizedTest
    @ValueSource(strings = {"/swagger-ui.html", "/swagger-ui/index.html", "/swagger-ui/swagger-ui-bundle.js",
            "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config", "/v3/api-docs/admin",
            "/swagger-resources", "/swagger-resources/configuration/ui", "/webjars/swagger-ui/index.html"})
    void documentationCannotBeReadWithoutCredentials(String path) throws Exception {
        var req = new MockHttpServletRequest("GET", path);
        req.addParameter("access_token", "not-an-auth-header");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        filter(mock(SupabaseTokenVerifier.class), mock(AdminUserService.class)).doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("Cache-Control")).contains("no-store");
        verifyNoInteractions(chain);
    }

    @Test
    void signedInAdminCanReadButNotWriteDocumentation() throws Exception {
        var verifier = mock(SupabaseTokenVerifier.class);
        when(verifier.isConfigured()).thenReturn(true);
        when(verifier.verify("session-token")).thenReturn(Map.of("email", "admin@example.com"));
        var users = mock(AdminUserService.class);
        var authorization = AdminUserService.Authorization.allowed(AdminUserRole.ADMIN, "registry", false);
        when(users.authorize(eq("admin@example.com"), anyList())).thenReturn(authorization);
        var req = new MockHttpServletRequest("GET", "/v3/api-docs");
        req.addHeader("Authorization", "Bearer session-token");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        filter(verifier, users).doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
        assertThat(res.getHeader("Cache-Control")).contains("no-store");
        assertThat(AdminAuthFilter.authorizeRequest(new MockHttpServletRequest("POST", "/v3/api-docs"),
                AdminUserRole.ADMIN, false)).isFalse();
    }

    @Test
    void ordinaryUsersAndInvalidSessionsCannotReadDocumentation() throws Exception {
        var verifier = mock(SupabaseTokenVerifier.class);
        when(verifier.isConfigured()).thenReturn(true);
        when(verifier.verify("invalid")).thenThrow(new IllegalArgumentException());
        when(verifier.verify("ordinary-user")).thenReturn(Map.of("email", "visitor@example.com"));
        var users = mock(AdminUserService.class);
        var denied = AdminUserService.Authorization.denied();
        when(users.authorize(eq("visitor@example.com"), anyList())).thenReturn(denied);
        for (var token : java.util.List.of("invalid", "ordinary-user")) {
            var req = new MockHttpServletRequest("GET", "/v3/api-docs");
            req.addHeader("Authorization", "Bearer " + token);
            var res = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);
            filter(verifier, users).doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(token.equals("invalid") ? 401 : 403);
            verifyNoInteractions(chain);
        }
        for (var role : java.util.List.of(AdminUserRole.EDITOR, AdminUserRole.PUBLISHER)) {
            assertThat(AdminAuthFilter.authorizeRequest(new MockHttpServletRequest("GET", "/v3/api-docs"),
                    role, false)).isFalse();
        }
    }

    private AdminAuthFilter filter(SupabaseTokenVerifier verifier, AdminUserService users) {
        return new AdminAuthFilter("", verifier, "", users);
    }
}
