package com.seatvault.service;

import com.seatvault.config.WaitingRoomProperties;
import com.seatvault.dto.queue.QueueStatusResponse;
import com.seatvault.exception.ForbiddenException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed virtual waiting room for events flagged {@code highDemand}.
 *
 * <h3>Data model</h3>
 * <ul>
 *   <li>{@code queue:wait:<eventId>} — a ZSET, FIFO by join time ({@code ZADD NX} so
 *       a repeated join doesn't reset the caller's position).</li>
 *   <li>{@code queue:admitted:<eventId>:<userId>} — a String key holding a random
 *       opaque token, TTL-bound. Its presence + value IS the admission: there's no
 *       separate "admitted" flag that could drift out of sync with the token.</li>
 *   <li>{@code queue:active-events} — a SET of event ids with a non-empty wait line,
 *       so {@link com.seatvault.scheduler.QueueAdmissionScheduler} only has to look
 *       at events that actually have someone waiting, not every high-demand event.</li>
 * </ul>
 *
 * <p>Admission tokens are re-checked (not consumed) on every hold attempt, so a
 * user can retry {@code /bookings/hold} — e.g. after losing a seat race — as many
 * times as they like within the TTL without re-joining the queue.
 */
@Service
public class QueueService {

    private static final String ACTIVE_EVENTS_KEY = "queue:active-events";

    private final StringRedisTemplate redis;
    private final WaitingRoomProperties properties;

    public QueueService(StringRedisTemplate redis, WaitingRoomProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Joins the FIFO wait line if not already queued or admitted; idempotent. */
    public QueueStatusResponse join(Long eventId, Long userId) {
        QueueStatusResponse admitted = admittedStatus(eventId, userId);
        if (admitted != null) {
            return admitted;
        }
        redis.opsForZSet().addIfAbsent(waitKey(eventId), userId.toString(), System.currentTimeMillis());
        redis.opsForSet().add(ACTIVE_EVENTS_KEY, eventId.toString());
        return status(eventId, userId);
    }

    public QueueStatusResponse status(Long eventId, Long userId) {
        QueueStatusResponse admitted = admittedStatus(eventId, userId);
        if (admitted != null) {
            return admitted;
        }
        Long rank = redis.opsForZSet().rank(waitKey(eventId), userId.toString());
        Long position = (rank == null) ? null : rank + 1;
        return new QueueStatusResponse(eventId, false, position, null, null);
    }

    /** Throws {@link ForbiddenException} unless {@code token} matches the caller's live admission token. */
    public void requireAdmission(Long eventId, Long userId, String token) {
        String stored = redis.opsForValue().get(admittedKey(eventId, userId));
        if (stored == null || token == null || !stored.equals(token)) {
            throw new ForbiddenException(
                    "This event is high-demand: join the waiting room (POST /queue/join) and wait to be "
                            + "admitted (GET /queue/status) before holding seats.");
        }
    }

    // ---- called by QueueAdmissionScheduler ----

    /** Admits up to the configured batch size of the longest-waiting users. Returns how many were admitted. */
    public int admitNextBatch(Long eventId) {
        Set<ZSetOperations.TypedTuple<String>> popped =
                redis.opsForZSet().popMin(waitKey(eventId), properties.admissionBatchSize());
        if (popped == null || popped.isEmpty()) {
            redis.opsForSet().remove(ACTIVE_EVENTS_KEY, eventId.toString());
            return 0;
        }
        Duration ttl = Duration.ofSeconds(properties.admissionTokenTtlSeconds());
        for (ZSetOperations.TypedTuple<String> entry : popped) {
            redis.opsForValue().set(admittedKey(eventId, entry.getValue()), UUID.randomUUID().toString(), ttl);
        }
        return popped.size();
    }

    public List<Long> activeHighDemandEventIds() {
        Set<String> ids = redis.opsForSet().members(ACTIVE_EVENTS_KEY);
        return (ids == null) ? List.of() : ids.stream().map(Long::valueOf).toList();
    }

    // ---- helpers ----

    private QueueStatusResponse admittedStatus(Long eventId, Long userId) {
        String key = admittedKey(eventId, userId);
        String token = redis.opsForValue().get(key);
        if (token == null) {
            return null;
        }
        Long ttl = redis.getExpire(key);
        long remaining = (ttl == null || ttl < 0) ? properties.admissionTokenTtlSeconds() : ttl;
        return new QueueStatusResponse(eventId, true, null, token, remaining);
    }

    private String waitKey(Long eventId) {
        return "queue:wait:" + eventId;
    }

    private String admittedKey(Long eventId, Long userId) {
        return admittedKey(eventId, userId.toString());
    }

    private String admittedKey(Long eventId, String userId) {
        return "queue:admitted:" + eventId + ":" + userId;
    }
}
