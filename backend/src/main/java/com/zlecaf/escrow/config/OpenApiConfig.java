package com.zlecaf.escrow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI escrowOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("ZLECAf B2B Escrow API")
                .description("Core engine for intra-African cross-border escrow transactions (POC).")
                .version("0.1.0")
                .contact(new Contact().name("Escrow POC").email("admin@escrow.local"))
                .license(new License().name("Proprietary")))
            // Apply the JWT scheme globally so every operation shows the lock icon.
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
            .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Paste the JWT returned by /api/v1/auth/login (no \"Bearer \" prefix).")));
    }
}
