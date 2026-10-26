package com.seatvault.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * A minimal Redis distributed lock (bonus path).
 *
 * <h3>When is DB locking alone insufficient?</h3>
 * <p>For THIS system, it usually isn't: every app instance talks to the same
 * PostgreSQL, so {@code SELECT ... FOR UPDATE} already serializes writers on a seat
 * row <em>across all instances</em>. The DB is the shared source of truth.
 *
 * <p>A Redis lock becomes useful when you want to:
 * <ul>
 *   <li><b>Shed load before the DB:</b> fail a doomed-to-conflict request at the
 *       app tier instead of letting hundreds of threads pile onto the same row
 *       lock and exhaust the connection pool.</li>
 *   <li><b>Coordinate work that spans more than the DB</b> (cache entries, files,
 *       calls to other services) where a single DB transaction can't span it.</li>
 *   <li><b>Guard non-transactional sections</b> (e.g. a scheduled job that must run
 *       on exactly one instance).</li>
 * </ul>
 *
 * <p>Implementation: {@code SET key token NX PX ttl} for atomic acquire, and a Lua
 * compare-and-delete for release so we only ever delete a lock we still own (a
 * naive {@code DEL} could drop someone else's lock after our TTL lapsed).
 */
@Service
public class RedisLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisLockService.class);

    /** Release only if the stored token matches ours (atomic check-and-del). */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** A held lock; release via {@link #release(Lock)}. */
    public record Lock(String key, String token) {
    }

    /**
     * Try to acquire {@code key} for {@code ttl}. Returns null if already held.
     */
    public Lock tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, ttl);
        if (Boolean.TRUE.equals(ok)) {
            return new Lock(key, token);
        }
        return null;
    }

    public void release(Lock lock) {
        if (lock == null) return;
        try {
            redis.execute(RELEASE_SCRIPT, List.of(lock.key()), lock.token());
        } catch (RuntimeException ex) {
            // A failed release is non-fatal: the TTL guarantees the lock frees itself.
            log.warn("Failed to release redis lock {} (will expire via TTL): {}", lock.key(), ex.getMessage());
        }
    }
}
