package com.seatvault.domain;

/**
 * Lifecycle of a single seat.
 *
 * <pre>
 *   AVAILABLE ──hold──▶ HELD ──pay──▶ BOOKED
 *       ▲                 │
 *       └──expire/cancel──┘
 * </pre>
 *
 * The only legal transitions are AVAILABLE→HELD, HELD→BOOKED, and HELD→AVAILABLE.
 * BOOKED is terminal for the life of the event. These rules are enforced in the
 * service layer (Phase 5); the enum just names the states.
 */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    BOOKED
}
