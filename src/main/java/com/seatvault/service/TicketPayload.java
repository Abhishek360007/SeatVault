package com.seatvault.service;

import java.time.Instant;

/** A decoded, signature-verified ticket token (see {@link TicketService}). */
public record TicketPayload(Long bookingId, Long seatId, Long eventId, Instant issuedAt) {
}
