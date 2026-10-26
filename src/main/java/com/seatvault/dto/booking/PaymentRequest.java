package com.seatvault.dto.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Simulated payment for a pending booking.
 *
 * @param paymentMethod  drives the simulated outcome: {@code FAIL_CARD} declines,
 *                       {@code TIMEOUT_CARD} simulates a provider timeout, anything
 *                       else approves. (See {@code SimulatedPaymentGateway}.)
 * @param idempotencyKey client-generated key making the charge exactly-once: two
 *                       requests carrying the same key for the same booking return
 *                       the first result instead of charging twice.
 */
public record PaymentRequest(

        @NotBlank(message = "paymentMethod is required")
        @Size(max = 40, message = "paymentMethod must be at most 40 characters")
        String paymentMethod,

        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 100, message = "idempotencyKey must be at most 100 characters")
        String idempotencyKey
) {
}
