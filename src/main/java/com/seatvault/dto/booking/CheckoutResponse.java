package com.seatvault.dto.booking;

import java.time.Instant;

/**
 * Everything the browser needs to open the provider's checkout widget for a
 * booking. The amount comes from the provider order, which was created from the
 * booking's frozen amount, so what is shown is exactly what will be charged.
 *
 * @param provider         {@code razorpay} or {@code simulated}; tells the UI which widget to open.
 * @param keyId            public key the widget is opened with (never the secret).
 * @param orderId          provider order to pay.
 * @param bookingReference human-facing booking code, also the order's receipt.
 * @param holdExpiresAt    when the seat hold lapses; payment must finish before then.
 */
public record CheckoutResponse(
        String provider,
        String keyId,
        String orderId,
        long amountMinor,
        String currency,
        Long bookingId,
        String bookingReference,
        String merchantName,
        String description,
        String customerName,
        String customerEmail,
        Instant holdExpiresAt
) {
}
