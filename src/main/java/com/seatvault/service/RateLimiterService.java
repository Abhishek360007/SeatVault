package com.seatvault.service;

import com.seatvault.config.RateLimitProperties;
import com.seatvault.exception.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Per-user fixed-window rate limiter backed by Redis.
 *
 * <h3>Algorithm</h3>
 * A counter key {@code rl:<bucket>:<userId>:<windowEpoch>} is incremented per
 * request; the first increment in a window also sets the key's TTL to the window
 * length. When the counter exceeds the configured max, the request is rejected with
 * 429. Because the key embeds the window's epoch, windows roll over automatically
 * and old keys expire on their own.
 *
 * <p>The check is done in a single atomic Lua script (INCR + conditional EXPIRE) so
 * concurrent requests from the same user can't race past the limit — important
 * because the whole point of this endpoint is correct behaviour under concurrency.
 *
 * <p><b>Why fixed-window?</b> It's simple, O(1) memory per user, and good enough to
 * shield the booking path from abuse/hot-looping clients. A sliding-window or token
 * bucket would smooth burst-at-boundary edges; for this use case the simplicity
 * wins and the trade-off is documented.
 *
 * <p>Redis being the store means the limit is shared across all app instances — a
 * user can't multiply their quota by hitting different nodes.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    /** INCR the counter; on first hit set EXPIRE. Returns the new count. */
    private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('incr', KEYS[1])
            if current == 1 then
                redis.call('expire', KEYS[1], ARGV[1])
            end
            return current
            """,
            Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RateLimiterService(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * Records one request for {@code userId} in the named {@code bucket} and throws
     * {@link TooManyRequestsException} if the limit is exceeded. No-op when disabled.
     */
    public void checkLimit(String bucket, Long userId) {
        checkLimitByKey(bucket, String.valueOf(userId), properties.maxRequests(), properties.windowSeconds());
    }

    /**
     * Records one request for {@code keyIdentifier} in the named {@code bucket} and throws
     * {@link TooManyRequestsException} if the limit is exceeded.
     */
    public void checkLimitByKey(String bucket, String keyIdentifier, int maxRequests, int windowSeconds) {
        if (!properties.enabled()) {
            return;
        }
        long windowEpoch = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = "rl:" + bucket + ":" + keyIdentifier + ":" + windowEpoch;

        Long count = redis.execute(INCR_SCRIPT, List.of(key), String.valueOf(windowSeconds));
        long current = (count == null) ? 0 : count;

        if (current > maxRequests) {
            long ttl = ttlSeconds(key, windowSeconds);
            log.debug("Rate limit exceeded for key={} bucket={} ({}/{} in {}s window)",
                    keyIdentifier, bucket, current, maxRequests, windowSeconds);
            throw new TooManyRequestsException(
                    "Rate limit exceeded: max " + maxRequests
                            + " requests per " + windowSeconds + "s. Try again in " + ttl + "s.",
                    ttl);
        }
    }

    private long ttlSeconds(String key, int window) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        return (ttl == null || ttl < 0) ? window : ttl;
    }
}
