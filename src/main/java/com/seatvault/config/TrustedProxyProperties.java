package com.seatvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds {@code app.security.trusted-proxies}: CIDR ranges (or bare IPs) of load
 * balancers/reverse proxies allowed to set {@code X-Forwarded-For}. Empty by default,
 * meaning the header is never trusted and {@code request.getRemoteAddr()} is used —
 * the safe default for a direct-exposed instance. Set this to your LB/ingress CIDR
 * in production so client IPs resolve correctly through it, without letting an
 * end user spoof the header to dodge IP-based rate limiting.
 */
@ConfigurationProperties(prefix = "app.security")
public record TrustedProxyProperties(List<String> trustedProxies) {

    public TrustedProxyProperties {
        trustedProxies = (trustedProxies == null) ? List.of() : List.copyOf(trustedProxies);
    }
}
