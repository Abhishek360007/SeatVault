package com.seatvault.security;

import com.seatvault.config.TrustedProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the "real" client IP for a request, used by IP-based rate limiting.
 *
 * <p>{@code X-Forwarded-For} is attacker-controlled input on any request that
 * reaches this app directly — trusting it unconditionally lets a client spoof a
 * fresh IP on every request and bypass IP-based rate limits entirely. This class
 * only honours the header when the immediate TCP peer ({@code remoteAddr}) is a
 * configured trusted proxy (see {@code app.security.trusted-proxies}); otherwise
 * it falls back to {@code remoteAddr}.
 */
@Component
public class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxyMatchers;

    public ClientIpResolver(TrustedProxyProperties properties) {
        this.trustedProxyMatchers = properties.trustedProxies().stream()
                .map(IpAddressMatcher::new)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isFromTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return remoteAddr;
    }

    private boolean isFromTrustedProxy(String remoteAddr) {
        if (trustedProxyMatchers.isEmpty() || remoteAddr == null) {
            return false;
        }
        return trustedProxyMatchers.stream().anyMatch(matcher -> matcher.matches(remoteAddr));
    }
}
