package com.seatvault.dto.ticket;

import jakarta.validation.constraints.NotBlank;

/** Scanner input: the {@code <payload>.<signature>} token decoded from a ticket's QR code. */
public record TicketVerifyRequest(
        @NotBlank(message = "token is required") String token
) {
}
