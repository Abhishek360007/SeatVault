package com.seatvault.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Revokes individual access tokens before they expire (used on logout).
 *
 * <p>Each access token carries a unique id ({@code jti}). Revoking it writes
 * {@code jwt:blocklist:<jti>} to Redis with a TTL equal to the token's remaining
 * lifetime, so the entry disappears exactly when the token would have expired anyway:
 * the blocklist never holds more than the live, revoked tokens. Redis is shared, so a
 * token revoked on one instance is rejected by all of them.
 *
 * <p><b>Redis outage:</b> the check fails <em>open</em> (logs a warning and treats
 * the token as not revoked). Failing closed would turn a Redis blip into every
 * authenticated request failing. The exposure is bounded by the 15-minute access
 * token TTL; refresh tokens are revoked in PostgreSQL and are unaffected.
 */
@Service
public class TokenBlocklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlocklistService.class);
    private static final String PREFIX = "jwt:blocklist:";

    private final StringRedisTemplate redis;

    public TokenBlocklistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Blocks {@code jti} until {@code expiresAt}. No-op for a missing id or an already-expired token. */
    public void revoke(String jti, Instant expiresAt) {
        if (jti == null || expiresAt == null) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return; // already expired: nothing to block
        }
        redis.opsForValue().set(PREFIX + jti, "1", ttl);
    }

    public boolean isRevoked(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
        } catch (RuntimeException ex) {
            log.warn("Token blocklist unavailable; allowing token (fail-open): {}", ex.toString());
            return false;
        }
    }
}
