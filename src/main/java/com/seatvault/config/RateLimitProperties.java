package com.seatvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.booking.rate-limit.*}.
 *
 * @param maxRequests   max allowed requests per user within the window.
 * @param windowSeconds length of the fixed window in seconds.
 * @param enabled       master switch (handy to disable in tests).
 */
@ConfigurationProperties(prefix = "app.booking.rate-limit")
public record RateLimitProperties(int maxRequests, int windowSeconds, boolean enabled) {

    public RateLimitProperties {
        if (maxRequests <= 0) maxRequests = 10;
        if (windowSeconds <= 0) windowSeconds = 60;
    }
}
