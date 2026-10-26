package com.seatvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/**
 * Type-safe binding of app.cors.* configurations.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
