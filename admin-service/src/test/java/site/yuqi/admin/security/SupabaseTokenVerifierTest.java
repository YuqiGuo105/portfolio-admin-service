package site.yuqi.admin.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupabaseTokenVerifierTest {
    static final String SECRET = "test-only-long-legacy-secret-kept-during-migration-123456";
    HttpServer server;
    ECKey ec;
    RSAKey rsa;
    SupabaseTokenVerifier verifier;
    String issuer;
    AtomicInteger keyReads;

    @BeforeEach void setup() throws Exception {
        ec = new ECKeyGenerator(Curve.P_256).keyID("ec-current").generate();
        rsa = new RSAKeyGenerator(2048).keyID("rsa-previous").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keyReads = new AtomicInteger();
        server.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
            keyReads.incrementAndGet();
            byte[] body = new JWKSet(List.of(ec.toPublicJWK(), rsa.toPublicJWK())).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        issuer = base + "/auth/v1";
        verifier = new SupabaseTokenVerifier(SECRET, base);
    }

    @AfterEach void stop() { server.stop(0); }

    JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(issuer).subject("test-user").audience("authenticated")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("email", "admin@example.test");
    }

    String sign(JWTClaimsSet claims, JWSAlgorithm alg, String kid, JWSSigner signer) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(kid).build(), claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    @Test void verifiesBothAsymmetricAlgorithmsAndCachesPublicKeys() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(verifier.verify(sign(claims().build(), JWSAlgorithm.ES256, ec.getKeyID(), new ECDSASigner(ec))))
                    .containsEntry("email", "admin@example.test");
            assertThat(verifier.verify(sign(claims().build(), JWSAlgorithm.RS256, rsa.getKeyID(), new RSASSASigner(rsa))))
                    .containsEntry("sub", "test-user");
        }
        assertThat(keyReads.get()).isEqualTo(1);
    }

    @Test void legacySessionsContinueWithoutNetwork() throws Exception {
        String token = sign(claims().build(), JWSAlgorithm.HS256, null, new MACSigner(SECRET));
        assertThat(verifier.verify(token)).containsEntry("sub", "test-user");
        assertThat(keyReads.get()).isZero();
    }

    @Test void rejectsWrongIssuerAudienceExpiryMissingClaimsAndAnonymousUsers() throws Exception {
        var invalid = List.of(claims().issuer("https://attacker.test").build(), claims().audience("another-client").build(),
                claims().expirationTime(Date.from(Instant.now().minusSeconds(120))).build(),
                claims().expirationTime(null).build(), claims().subject(null).build(),
                claims().claim("is_anonymous", true).build());
        for (var claims : invalid) {
            String token = sign(claims, JWSAlgorithm.ES256, ec.getKeyID(), new ECDSASigner(ec));
            assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void rejectsTamperedAndUnknownKeysAndUnconfiguredVerifier() throws Exception {
        var foreign = new ECKeyGenerator(Curve.P_256).keyID(ec.getKeyID()).generate();
        String tampered = sign(claims().build(), JWSAlgorithm.ES256, ec.getKeyID(), new ECDSASigner(foreign));
        String unknown = sign(claims().build(), JWSAlgorithm.ES256, "unknown", new ECDSASigner(ec));
        for (String token : List.of(tampered, unknown, "not-a-jwt", new PlainJWT(claims().build()).serialize())) {
            assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(RuntimeException.class);
        }
        assertThat(new SupabaseTokenVerifier("", "").isConfigured()).isFalse();
        assertThatThrownBy(() -> new SupabaseTokenVerifier("", "").verify(tampered)).isInstanceOf(RuntimeException.class);
    }

    @Test void identityTokensCannotBeUsedAsAccessTokens() throws Exception {
        String idToken = sign(claims().audience("oauth-client-id").build(), JWSAlgorithm.ES256, ec.getKeyID(), new ECDSASigner(ec));
        assertThatThrownBy(() -> verifier.verify(idToken)).isInstanceOf(RuntimeException.class);
    }

    @Test void es256RequestsStillRequireManagedAdminAuthorization() throws Exception {
        var users = mock(site.yuqi.admin.service.AdminUserService.class);
        var filter = new AdminAuthFilter("", verifier, "", users);
        String token = sign(claims().build(), JWSAlgorithm.ES256, ec.getKeyID(), new ECDSASigner(ec));
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/admin/users/me");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        when(users.authorize("admin@example.test", List.of())).thenReturn(
                site.yuqi.admin.service.AdminUserService.Authorization.denied());
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain);

        when(users.authorize("admin@example.test", List.of())).thenReturn(
                site.yuqi.admin.service.AdminUserService.Authorization.allowed(
                        site.yuqi.admin.domain.AdminUserRole.ADMIN, "managed_admin", false));
        response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }
}
