package site.yuqi.admin.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI portfolioAdminOpenAPI() {
        SecurityScheme adminSecret = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-Admin-Secret");

        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        return new OpenAPI()
                .info(new Info()
                        .title("Portfolio Admin Service")
                        .version("0.0.1-SNAPSHOT")
                        .description("Admin content platform: versioning, outbox, indexing, audit."))
                .components(new Components()
                        .addSecuritySchemes("AdminSecret", adminSecret)
                        .addSecuritySchemes("BearerAuth", bearer))
                .addSecurityItem(new SecurityRequirement().addList("AdminSecret"))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"));
    }
}
