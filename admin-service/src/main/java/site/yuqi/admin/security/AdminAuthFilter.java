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
 * Guards {@code /api/admin/**} endpoints with a <strong>dual auth channel</strong>:
 * <ol>
 *     <li><strong>Primary (browser):</strong>
 *         {@code Authorization: Bearer <Supabase HS256 JWT>} whose {@code email}
 *         claim is in {@code portfolio.supabase.allowed-emails}. This is the path
 *         used by the Portfolio admin panel and Mr. Pot chat widget, which obtain
 *         the JWT via {@code supabase.auth.signInWithPassword(...)}.</li>
 *     <li><strong>Fallback (server-to-server / scripts):</strong>
 *         {@code X-Admin-Secret} header equal to {@code portfolio.admin.secret}.
 *         Use this only for internal automation, smoke tests, or CI hooks.</li>
 * </ol>
 *
 * <p>The whitelist {@link #PUBLIC_PREFIXES} (Swagger UI, OpenAPI, actuator)
 * is unauthenticated by design — Swagger UI itself drives the {@code Authorize}
 * dialog where humans paste a Supabase Bearer token before calling
 * {@code /api/admin/**}.
 *
 * <p>Error responses distinguish three failure modes so the frontend can react
 * sensibly:
 * <ul>
 *     <li>{@code 401 missing_credentials} — neither header was supplied; the UI
 *         should redirect the user to the Portfolio Supabase login.</li>
 *     <li>{@code 401 invalid_token} — Bearer header was present but the JWT
 *         failed signature/expiry checks; the UI should clear the session and
 *         re-prompt for login.</li>
 *     <li>{@code 403 forbidden_email} — JWT was valid but the email is not in
 *         the allow-list; the UI should show "your account is not authorised".</li>
 * </ul>
 *
 * <p>The resolved actor (email or {@code admin-secret}) is stored on the request
 * via {@link AdminPrincipal#ATTR} for downstream audit logging.
 *
 * <p>TODO(prod): replace the email allow-list with a Supabase {@code admin_users}
 * table once role-based admin onboarding is needed.
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

        // Primary path: Bearer Supabase JWT (browser & chat widget).
        String authz = req.getHeader("Authorization");
        if (authz != null && authz.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (supabaseKey == null) {
                writeError(res, 503, "auth_unconfigured",
                        "Supabase JWT verification is not configured on the server.");
                return;
            }
            String token = authz.substring(7).trim();
            if (token.isEmpty()) {
                writeError(res, 401, "invalid_token", "Empty Bearer token.");
                return;
            }
            try {
                Jws<Claims> jws = Jwts.parser()
                        .verifyWith(supabaseKey)
                        .build()
                        .parseSignedClaims(token);
                Claims claims = jws.getPayload();
                String email = lowerStr(claims.get("email"));
                if (email == null) {
                    writeError(res, 401, "invalid_token",
                            "Token has no email claim; sign in via Portfolio again.");
                    return;
                }
                if (!allowedEmails.contains(email)) {
                    writeError(res, 403, "forbidden_email",
                            "Account " + email + " is not authorised for admin access.");
                    return;
                }
                req.setAttribute(AdminPrincipal.ATTR, email);
                chain.doFilter(req, res);
                return;
            } catch (Exception e) {
                log.debug("Supabase JWT validation failed: {}", e.getMessage());
                writeError(res, 401, "invalid_token",
                        "Supabase session is invalid or expired; please sign in again.");
                return;
            }
        }

        // Fallback path: X-Admin-Secret (server-to-server / internal scripts).
        String headerSecret = req.getHeader("X-Admin-Secret");
        if (headerSecret != null) {
            if (!adminSecret.isEmpty() && constantTimeEquals(headerSecret, adminSecret)) {
                req.setAttribute(AdminPrincipal.ATTR, "admin-secret");
                chain.doFilter(req, res);
                return;
            }
            writeError(res, 401, "invalid_token", "Invalid X-Admin-Secret.");
            return;
        }

        writeError(res, 401, "missing_credentials",
                "Provide a Supabase Bearer JWT (preferred) or an X-Admin-Secret header.");
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
