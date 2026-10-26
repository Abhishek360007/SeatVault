package com.seatvault.domain;

/**
 * Lifecycle of a single payment attempt.
 *
 * <pre>
 *   INITIATED ──gateway approves──▶ APPROVED   (terminal, booking confirmed)
 *      │
 *      ├──gateway declines────────▶ DECLINED   (terminal, booking stays PENDING)
 *      └──unexpected error────────▶ FAILED     (terminal, retry with a new key)
 * </pre>
 *
 * The row is created in {@code INITIATED} the moment we claim the idempotency slot
 * (before charging), so a crash mid-charge leaves an auditable record and a
 * same-key retry can reason about the in-flight attempt rather than charging again.
 */
public enum PaymentStatus {
    INITIATED,
    APPROVED,
    DECLINED,
    FAILED
}
