package com.seatvault.dto.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The browser reporting a failed attempt in the provider's widget, so the reason is
 * kept with the payment. Display-only: it never changes what is charged or confirmed.
 */
public record PaymentFailureRequest(
        @NotBlank @Size(max = 64) String orderId,
        @Size(max = 255) String reason
) {
}
