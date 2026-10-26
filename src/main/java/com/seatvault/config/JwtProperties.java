package com.seatvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding of the {@code app.jwt.*} settings from application.yml.
 * Using {@code @ConfigurationProperties} (over scattered {@code @Value}) keeps
 * config validated and discoverable in one place.
 *
 * @param secret            HS256 signing key; must be >= 256 bits (32+ chars).
 * @param expirationMinutes token time-to-live.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        long expirationMinutes,
        long refreshExpirationDays
) {
}
