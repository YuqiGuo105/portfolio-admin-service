package site.yuqi.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Restricts request-scoped worker controls without granting admin privileges. */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PREFIX = "/api/internal/";
    private static final String HEADER = "X-Internal-Token";

    private final String expected;

    public InternalTokenFilter(@Value("${portfolio.internal.token:}") String expected) {
        this.expected = expected == null ? "" : expected.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (expected.isEmpty()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "internal token is not configured");
            return;
        }
        String actual = request.getHeader(HEADER);
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.trim().getBytes(StandardCharsets.UTF_8))) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid or missing X-Internal-Token");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
