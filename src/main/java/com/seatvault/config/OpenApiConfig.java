package com.seatvault.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger metadata and the JWT security scheme.
 *
 * <p>The controllers annotate protected operations with
 * {@code @SecurityRequirement(name = "bearerAuth")}; this class is what actually
 * <em>defines</em> that scheme, so Swagger UI shows an <b>Authorize</b> button and
 * sends {@code Authorization: Bearer <token>} on "try it out" calls. Without this
 * bean the name would dangle and the UI would offer no way to authenticate.
 *
 * <p>Browse the docs at {@code /swagger-ui.html}; the raw spec is at
 * {@code /v3/api-docs}. Both paths are public (see {@code SecurityConfig}).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI seatVaultOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SeatVault API")
                        .version("0.1.0")
                        .description("""
                                Production-grade concurrent seat booking API.

                                **Auth:** call `POST /api/v1/auth/register` or `/login` to get a JWT,
                                click **Authorize**, paste the token, and the UI will send it as a
                                Bearer header on protected endpoints.

                                **Roles:** event writes require `ADMIN`; booking endpoints require an
                                authenticated `USER`. A bootstrap admin is seeded on first startup.

                                The headline feature is **no overselling under concurrency** — see the
                                README for the locking design.""")
                        .contact(new Contact().name("Abhishek Raj").email("abhiraj360007@gmail.com").url("https://github.com/Abhishek360007"))
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the JWT returned by /api/v1/auth/login or /register")));
    }
}
