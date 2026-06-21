package site.yuqi.admin.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares two security schemes for the admin OpenAPI surface:
 * <ul>
 *     <li><strong>BearerAuth</strong> (HTTP / JWT) — the <em>preferred</em> path
 *         for human operators using Swagger UI. Sign in at
 *         <a href="https://www.yuqi.site">yuqi.site</a> first, copy the Supabase
 *         {@code access_token} from DevTools, then click <em>Authorize</em> and
 *         paste {@code Bearer <token>}.</li>
 *     <li><strong>AdminSecret</strong> (apiKey in {@code X-Admin-Secret}) —
 *         secondary path for server-to-server scripts, CI smoke tests, and
 *         internal automation. Do not use from a browser.</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI portfolioAdminOpenAPI() {
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description(
                        "Supabase HS256 JWT. Sign in at https://www.yuqi.site, then run "
                        + "`(await supabase.auth.getSession()).data.session.access_token` "
                        + "in DevTools, and paste it here as `Bearer <token>`. Only emails "
                        + "in ADMIN_ALLOWED_EMAILS may proceed.");

        SecurityScheme adminSecret = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-Admin-Secret")
                .description(
                        "Fallback shared-secret header for server-to-server automation "
                        + "(CI, scripts). Do not use from a browser. Value must match the "
                        + "ADMIN_SECRET environment variable.");

        return new OpenAPI()
                .info(new Info()
                        .title("Portfolio Admin Service")
                        .version("0.0.1-SNAPSHOT")
                        .description(
                                "Admin content platform: versioning, outbox, indexing, audit.\n\n"
                                + "**Auth (try in this order):**\n"
                                + "1. **BearerAuth (preferred)** — Supabase JWT obtained by "
                                + "signing in to the Portfolio. Used by the Next.js admin "
                                + "panel and the Mr. Pot chat widget.\n"
                                + "2. **AdminSecret (fallback)** — `X-Admin-Secret` header, "
                                + "for internal scripts and CI only."))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth", bearer)
                        .addSecuritySchemes("AdminSecret", adminSecret))
                // BearerAuth listed first so Swagger UI's `Authorize` dialog defaults to it.
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
                .addSecurityItem(new SecurityRequirement().addList("AdminSecret"));
    }
}
