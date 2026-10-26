package com.seatvault.domain;

/**
 * Lifecycle of a booking.
 *
 * <pre>
 *   PENDING ──pay──▶ CONFIRMED
 *      │
 *      ├──hold expires──▶ EXPIRED
 *      └──user cancels──▶ CANCELLED
 * </pre>
 *
 * A booking is created in PENDING state the moment a hold is placed, so there is
 * always a durable record tying held seats to a user and an expiry. Only PENDING
 * bookings can transition; CONFIRMED / EXPIRED / CANCELLED are terminal.
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    EXPIRED,
    CANCELLED
}
