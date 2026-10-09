package site.yuqi.admin.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import site.yuqi.admin.service.AdminUserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminAuthFilterPathTest {
    @ParameterizedTest
    @ValueSource(strings = {"/api/%61dmin/users", "/api/%2561dmin/users", "/api/admin;v=1/users",
            "/api/public/../admin/users", "//api/admin/users", "/api/admin/%2e%2e/users",
            "/api/health/../admin/users", "/api/internal;v=1/workers/drain"})
    void rejectsAmbiguousPathsBeforeDispatch(String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-Admin-Secret", "test-secret");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new AdminAuthFilter("test-secret", "", "", mock(AdminUserService.class))
                .doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(400);
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/users", "/api/health-admin", "/new-private-api"})
    void unknownAndAdminRoutesRequireCredentials(String path) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new AdminAuthFilter("test-secret", "", "", mock(AdminUserService.class))
                .doFilter(new MockHttpServletRequest("GET", path), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void healthRemainsPublic() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/health");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new AdminAuthFilter("test-secret", "", "", mock(AdminUserService.class))
                .doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }
}
