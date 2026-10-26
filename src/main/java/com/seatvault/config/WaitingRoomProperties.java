package com.seatvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.waiting-room.*}: the virtual waiting room applied to events
 * flagged {@code highDemand}.
 *
 * @param admissionBatchSize     how many queued users {@link com.seatvault.scheduler.QueueAdmissionScheduler}
 *                               admits per tick.
 * @param admissionIntervalMs    delay between admission ticks.
 * @param admissionInitialDelayMs delay before the first tick after startup.
 * @param admissionTokenTtlSeconds how long an admission token stays valid — the caller
 *                               must complete {@code /bookings/hold} within this window
 *                               or re-poll {@code /queue/status} (they stay admitted,
 *                               a fresh token/TTL is issued) — matches the "short-lived
 *                               admission token" behaviour described to users.
 */
@ConfigurationProperties(prefix = "app.waiting-room")
public record WaitingRoomProperties(
        int admissionBatchSize,
        long admissionIntervalMs,
        long admissionInitialDelayMs,
        long admissionTokenTtlSeconds
) {
    public WaitingRoomProperties {
        if (admissionBatchSize <= 0) admissionBatchSize = 50;
        if (admissionIntervalMs <= 0) admissionIntervalMs = 10_000;
        if (admissionInitialDelayMs <= 0) admissionInitialDelayMs = 15_000;
        if (admissionTokenTtlSeconds <= 0) admissionTokenTtlSeconds = 120;
    }
}
