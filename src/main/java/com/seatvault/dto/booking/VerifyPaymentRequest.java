package com.seatvault.dto.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the provider's widget hands the browser after a successful payment
 * (Razorpay's {@code razorpay_order_id}, {@code razorpay_payment_id},
 * {@code razorpay_signature}). The server re-checks all of it; none is trusted as is.
 */
public record VerifyPaymentRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 64) String paymentId,
        @NotBlank @Size(max = 128) String signature
) {
}
