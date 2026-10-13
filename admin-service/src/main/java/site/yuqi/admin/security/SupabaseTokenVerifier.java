package site.yuqi.admin.security;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Verifies new JWKS-signed sessions while existing HS256 sessions expire normally. */
@Component
public class SupabaseTokenVerifier {
    private final NimbusJwtDecoder legacy;
    private final NimbusJwtDecoder asymmetric;

    public SupabaseTokenVerifier(
            @Value("${portfolio.supabase.jwt-secret:}") String secret,
            @Value("${portfolio.supabase.url:}") String supabaseUrl) {
        String base = supabaseUrl == null ? "" : supabaseUrl.trim().replaceAll("/+$", "");
        String issuer = base.isEmpty() ? "" : base + "/auth/v1";
        legacy = secret == null || secret.isBlank() ? null : NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        // The URL comes only from server configuration, never from JWT jku/iss headers.
        asymmetric = issuer.isEmpty() ? null : NimbusJwtDecoder
                .withJwkSetUri(issuer + "/.well-known/jwks.json")
                .jwsAlgorithms(algorithms -> {
                    algorithms.clear();
                    algorithms.add(SignatureAlgorithm.ES256);
                    algorithms.add(SignatureAlgorithm.RS256);
                })
                .restOperations(new RestTemplateBuilder()
                        .setConnectTimeout(Duration.ofSeconds(2))
                        .setReadTimeout(Duration.ofSeconds(3)).build())
                .build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid = jwt.getExpiresAt() != null && jwt.getSubject() != null
                    && !jwt.getSubject().isBlank() && jwt.getAudience().contains("authenticated")
                    && !Boolean.TRUE.equals(jwt.getClaim("is_anonymous"));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "A signed-in Supabase access token is required.", null));
        };
        var validator = new DelegatingOAuth2TokenValidator<>(
                issuer.isEmpty() ? JwtValidators.createDefault() : JwtValidators.createDefaultWithIssuer(issuer), claims);
        if (legacy != null) legacy.setJwtValidator(validator);
        if (asymmetric != null) asymmetric.setJwtValidator(validator);
    }

    public boolean isConfigured() {
        return legacy != null || asymmetric != null;
    }

    public Map<String, Object> verify(String token) {
        if (token == null || token.length() > 16384) throw new BadJwtException("Invalid access token");
        try {
            String algorithm = SignedJWT.parse(token).getHeader().getAlgorithm().getName();
            NimbusJwtDecoder decoder = switch (algorithm) {
                case "HS256" -> legacy;
                case "ES256", "RS256" -> asymmetric;
                default -> null;
            };
            if (decoder == null) throw new BadJwtException("Unsupported access token signature");
            return decoder.decode(token).getClaims();
        } catch (java.text.ParseException e) {
            throw new BadJwtException("Invalid access token");
        }
    }
}
