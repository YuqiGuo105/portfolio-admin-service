package site.yuqi.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import site.yuqi.admin.dto.ApiError;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Guards {@code /api/admin/**} endpoints with TWO accepted credentials:
 * <ol>
 *     <li>{@code X-Admin-Secret} header equal to {@code portfolio.admin.secret} (MVP),</li>
 *     <li>{@code Authorization: Bearer <Supabase HS256 JWT>} whose {@code email}
 *     claim is in {@code portfolio.supabase.allowed-emails} (future-ready).</li>
 * </ol>
 *
 * <p>Either credential is sufficient. The actor (email or {@code admin-secret})
 * is stored on the request via {@link AdminPrincipal#ATTR}.
 *
 * <p>TODO(prod): replace allow-list with a Supabase {@code admin_users} table.
 */
@Slf4j
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/health",
            "/actuator",
            "/swagger-ui",
            "/v3/api-docs",
            "/swagger-resources",
            "/webjars"
    );

    private final String adminSecret;
    private final SecretKey supabaseKey; // nullable
    private final List<String> allowedEmails;

    public AdminAuthFilter(
            @Value("${portfolio.admin.secret:}") String adminSecret,
            @Value("${portfolio.supabase.jwt-secret:}") String supabaseJwtSecret,
            @Value("${portfolio.supabase.allowed-emails:}") String allowedEmailsCsv
    ) {
        this.adminSecret = adminSecret == null ? "" : adminSecret.trim();
        this.allowedEmails = allowedEmailsCsv == null || allowedEmailsCsv.isBlank()
                ? List.of()
                : Arrays.stream(allowedEmailsCsv.split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).map(String::toLowerCase).toList();
        this.supabaseKey = buildKey(supabaseJwtSecret);
    }

    private static SecretKey buildKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            bytes = raw.trim().getBytes(StandardCharsets.UTF_8);
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return PUBLIC_PREFIXES.stream().anyMatch(p::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String path = req.getRequestURI();
        if (!path.startsWith("/api/admin")) {
            chain.doFilter(req, res);
            return;
        }

        // Path 1: X-Admin-Secret header
        String headerSecret = req.getHeader("X-Admin-Secret");
        if (headerSecret != null && !adminSecret.isEmpty() && constantTimeEquals(headerSecret, adminSecret)) {
            req.setAttribute(AdminPrincipal.ATTR, "admin-secret");
            chain.doFilter(req, res);
            return;
        }

        // Path 2: Bearer Supabase JWT
        String authz = req.getHeader("Authorization");
        if (authz != null && authz.regionMatches(true, 0, "Bearer ", 0, 7) && supabaseKey != null) {
            String token = authz.substring(7).trim();
            try {
                Jws<Claims> jws = Jwts.parser()
                        .verifyWith(supabaseKey)
                        .build()
                        .parseSignedClaims(token);
                Claims claims = jws.getPayload();
                String email = lowerStr(claims.get("email"));
                if (email != null && allowedEmails.contains(email)) {
                    req.setAttribute(AdminPrincipal.ATTR, email);
                    chain.doFilter(req, res);
                    return;
                }
                writeError(res, 403, "forbidden", "Account not authorized");
                return;
            } catch (Exception e) {
                log.debug("Supabase JWT validation failed: {}", e.getMessage());
                writeError(res, 401, "unauthorized", "Invalid Supabase token");
                return;
            }
        }

        writeError(res, 401, "unauthorized", "Missing X-Admin-Secret or valid Bearer token");
    }

    private static String lowerStr(Object o) {
        return o == null ? null : String.valueOf(o).toLowerCase();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }

    private void writeError(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write(MAPPER.writeValueAsString(ApiError.of(code, message)));
    }
}
