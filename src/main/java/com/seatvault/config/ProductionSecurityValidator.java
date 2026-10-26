package com.seatvault.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails fast on startup in the {@code prod} profile if the app is still running with
 * dev-only default secrets. These defaults exist so the jar boots without config in
 * local/dev, but shipping them to production would mean an admin account with a known
 * password and a JWT signing key an attacker can find in this repo.
 */
@Component
@Profile("prod")
public class ProductionSecurityValidator {

    private final JwtProperties jwtProperties;
    private final TicketProperties ticketProperties;
    private final String adminPassword;

    public ProductionSecurityValidator(JwtProperties jwtProperties,
                                        TicketProperties ticketProperties,
                                        @Value("${app.admin.password}") String adminPassword) {
        this.jwtProperties = jwtProperties;
        this.ticketProperties = ticketProperties;
        this.adminPassword = adminPassword;
    }

    @PostConstruct
    void validate() {
        if (jwtProperties.secret() == null || jwtProperties.secret().contains("change-me")) {
            throw new IllegalStateException(
                    "Refusing to start in 'prod' profile: JWT_SECRET is missing or still contains "
                            + "'change-me'. Set a unique, random secret (>= 256 bits) via the JWT_SECRET env var.");
        }
        if (ticketProperties.hmacSecret() == null || ticketProperties.hmacSecret().contains("change-me")) {
            throw new IllegalStateException(
                    "Refusing to start in 'prod' profile: TICKET_HMAC_SECRET is missing or still contains "
                            + "'change-me'. Set a unique, random secret via the TICKET_HMAC_SECRET env var.");
        }
        if ("admin12345".equals(adminPassword)) {
            throw new IllegalStateException(
                    "Refusing to start in 'prod' profile: ADMIN_PASSWORD is left at the default "
                            + "'admin12345'. Set a strong password via the ADMIN_PASSWORD env var.");
        }
    }
}
