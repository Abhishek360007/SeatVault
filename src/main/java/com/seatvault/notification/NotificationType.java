package com.seatvault.notification;

/** The emails SeatVault sends. Each is sent at most once per booking (see the dedupe key). */
public enum NotificationType {
    /** Seats booked; carries the PDF tickets. */
    BOOKING_CONFIRMED,
    /** What was paid, how, and the provider's payment id. */
    PAYMENT_RECEIPT,
    /** The customer cancelled a pending booking and its seats were released. */
    BOOKING_CANCELLED,
    /** Money arrived after the hold ended and was returned. */
    PAYMENT_REFUNDED,
    /** The event starts soon; carries the PDF tickets again. */
    EVENT_REMINDER
}
