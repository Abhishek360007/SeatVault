package com.seatvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.booking.*}.
 *
 * @param holdDurationMinutes  how long a hold survives before the scheduled job
 *                             (Phase 6) releases it.
 * @param lockingStrategy      PESSIMISTIC (default) or OPTIMISTIC — selects how
 *                             the reservation guards against oversell.
 * @param optimisticMaxRetries attempts before giving up when the optimistic path
 *                             keeps colliding (ignored for the pessimistic path).
 * @param maxSeatsPerBooking   guard rail on how many seats one request may hold.
 * @param redisLockEnabled     when true, the hold path additionally acquires a
 *                             Redis lock per seat (see {@code RedisLockService}).
 */
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(
        int holdDurationMinutes,
        LockingStrategy lockingStrategy,
        int optimisticMaxRetries,
        int maxSeatsPerBooking,
        boolean redisLockEnabled
) {
    public enum LockingStrategy {
        /** SELECT ... FOR UPDATE: writers serialize on the rows; no retries. */
        PESSIMISTIC,
        /** @Version check on flush; collisions retried by the service. */
        OPTIMISTIC
    }

    public BookingProperties {
        if (holdDurationMinutes <= 0) holdDurationMinutes = 5;
        if (lockingStrategy == null) lockingStrategy = LockingStrategy.PESSIMISTIC;
        if (optimisticMaxRetries <= 0) optimisticMaxRetries = 5;
        if (maxSeatsPerBooking <= 0) maxSeatsPerBooking = 10;
    }
}
