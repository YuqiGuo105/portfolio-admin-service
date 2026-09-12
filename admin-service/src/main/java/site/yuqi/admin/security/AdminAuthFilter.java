package site.yuqi.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import site.yuqi.admin.service.AdminUserService;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Guards {@code /api/admin/**} endpoints with a <strong>dual auth channel</strong>:
 * <ol>
 *     <li><strong>Primary (browser):</strong>
 *         {@code Authorization: Bearer <Supabase HS256 JWT>} whose {@code email}
 *         claim resolves to an active {@code admin_users} row, a configured
 *         owner, or the break-glass allow-list. This is the path used by the
 *         Portfolio admin panel and Mr. Pot chat widget.</li>
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
 *     <li>{@code 403 forbidden_email} — JWT was valid but the identity is not
 *         an active administrator.</li>
 *     <li>{@code 403 forbidden_role} — identity is an administrator but lacks
 *         the role or owner capability required by the operation.</li>
 * </ul>
 *
 * <p>The resolved actor (email or {@code admin-secret}) is stored on the request
 * via {@link AdminPrincipal#ATTR} for downstream audit logging.
 *
 * <p>Runtime authorization is data-driven through {@code admin_users}; the
 * email allow-list remains as a break-glass fallback.
 */
@Slf4j
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    // Register JavaTimeModule so we can serialize the Instant timestamp on ApiError.
    // A bare new ObjectMapper() throws InvalidDefinitionException on java.time.Instant,
    // which previously turned every 401/403 from this filter into a Spring-default 500.
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

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
    private final AdminUserService adminUserService;

    public AdminAuthFilter(
            @Value("${portfolio.admin.secret:}") String adminSecret,
            @Value("${portfolio.supabase.jwt-secret:}") String supabaseJwtSecret,
            @Value("${portfolio.supabase.allowed-emails:}") String allowedEmailsCsv,
            AdminUserService adminUserService
    ) {
        this.adminSecret = adminSecret == null ? "" : adminSecret.trim();
        this.adminUserService = adminUserService;
        this.allowedEmails = allowedEmailsCsv == null || allowedEmailsCsv.isBlank()
                ? List.of()
                : Arrays.stream(allowedEmailsCsv.split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).map(String::toLowerCase).toList();
        this.supabaseKey = buildKey(supabaseJwtSecret);
    }

    private static SecretKey buildKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Supabase signs JWTs using the JWT secret's raw UTF-8 bytes as the
        // HMAC key (same convention as supabase-js / PostgREST / GoTrue). The
        // previous "try Base64 decode first, fall back to UTF-8" heuristic
        // silently corrupted secrets that happened to be valid base64 strings
        // (e.g. an 88-char secret ending in '==' decodes cleanly to 64 random
        // bytes but Supabase actually signed with the 88 ASCII bytes), turning
        // every authenticated request into a 401 invalid_token. Just trust the
        // raw bytes — if the dashboard hands you a base64-encoded secret, paste
        // it verbatim and it Just Works because Supabase HMACs the verbatim
        // characters either way.
        byte[] bytes = raw.trim().getBytes(StandardCharsets.UTF_8);
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

        // Admin responses can contain account, audit and operational data.
        // Prevent browser/proxy caching and MIME sniffing on both success and
        // failure responses.
        res.setHeader("Cache-Control", "private, no-store, max-age=0");
        res.setHeader("Pragma", "no-cache");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");

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
            Jws<Claims> jws;
            try {
                jws = Jwts.parser()
                        .verifyWith(supabaseKey)
                        .build()
                        .parseSignedClaims(token);
            } catch (Exception e) {
                log.debug("Supabase JWT validation failed: {}", e.getMessage());
                writeError(res, 401, "invalid_token",
                        "Supabase session is invalid or expired; please sign in again.");
                return;
            }

            Claims claims = jws.getPayload();
            String email = lowerStr(claims.get("email"));
            if (email == null) {
                writeError(res, 401, "invalid_token",
                        "Token has no email claim; sign in via Portfolio again.");
                return;
            }
            AdminUserService.Authorization authorization =
                    adminUserService.authorize(email, allowedEmails);
            if (!authorization.allowed()) {
                writeError(res, 403, "forbidden_email",
                        "This account is not authorised for admin access.");
                return;
            }
            req.setAttribute(AdminPrincipal.ATTR, email);
            req.setAttribute(AdminPrincipal.ROLE_ATTR, authorization.role().name());
            req.setAttribute(AdminPrincipal.OWNER_ATTR, authorization.owner());
            req.setAttribute(AdminPrincipal.AUTH_SOURCE_ATTR, authorization.source());
            if (!authorizeRequest(req, authorization.role(), authorization.owner())) {
                writeError(res, 403, "forbidden_role",
                        "Your admin role does not permit this operation.");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        // Fallback path: X-Admin-Secret (server-to-server / internal scripts).
        String headerSecret = req.getHeader("X-Admin-Secret");
        if (headerSecret != null) {
            if (!adminSecret.isEmpty() && constantTimeEquals(headerSecret, adminSecret)) {
                req.setAttribute(AdminPrincipal.ATTR, "admin-secret");
                req.setAttribute(AdminPrincipal.ROLE_ATTR, "ADMIN");
                req.setAttribute(AdminPrincipal.OWNER_ATTR, false);
                req.setAttribute(AdminPrincipal.AUTH_SOURCE_ATTR, "admin_secret");
                if (!authorizeRequest(req, site.yuqi.admin.domain.AdminUserRole.ADMIN, false)) {
                    writeError(res, 403, "forbidden_role",
                            "The internal admin credential cannot manage administrator identities.");
                    return;
                }
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

    static boolean authorizeRequest(
            HttpServletRequest request,
            site.yuqi.admin.domain.AdminUserRole role,
            boolean owner
    ) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.equals("/api/admin/knowledge") || path.startsWith("/api/admin/knowledge/")) {
            return atLeast(role, site.yuqi.admin.domain.AdminUserRole.ADMIN);
        }

        boolean currentUserEndpoint = path.equals("/api/admin/users/me")
                || path.startsWith("/api/admin/users/me/");
        if (path.startsWith("/api/admin/users") && !currentUserEndpoint) {
            return owner;
        }
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }
        if (path.startsWith("/api/admin/content") || path.startsWith("/api/admin/projects")) {
            if (path.contains("/publish") || path.contains("/reindex-")) {
                return atLeast(role, site.yuqi.admin.domain.AdminUserRole.PUBLISHER);
            }
            return atLeast(role, site.yuqi.admin.domain.AdminUserRole.EDITOR);
        }
        return atLeast(role, site.yuqi.admin.domain.AdminUserRole.ADMIN);
    }

    private static boolean atLeast(
            site.yuqi.admin.domain.AdminUserRole actual,
            site.yuqi.admin.domain.AdminUserRole required
    ) {
        return actual != null && actual.ordinal() >= required.ordinal();
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
