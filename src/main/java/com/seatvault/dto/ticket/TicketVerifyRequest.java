package com.seatvault.dto.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Scanner input: the {@code <payload>.<signature>} token decoded from a ticket's QR
 * code, and the event this gate is admitting for. A genuine ticket for a different
 * event is refused, never checked in.
 */
public record TicketVerifyRequest(
        @NotBlank(message = "token is required") @Size(max = 512) String token,
        @NotNull(message = "eventId is required: the event this gate is checking in") Long eventId
) {
}
