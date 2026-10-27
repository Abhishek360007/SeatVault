package com.seatvault.dto.ticket;

import java.time.Instant;

/**
 * One seat's on-screen ticket, for the owner's "show at the gate" view. The QR
 * encodes the same signed token as the PDF, so either can be scanned.
 *
 * @param qrPng       the QR as a {@code data:image/png;base64,...} URI.
 * @param checkedInAt when this seat was admitted, or null if not yet.
 */
public record TicketPass(
        Long seatId,
        String seatLabel,
        String tierName,
        String token,
        String qrPng,
        Instant checkedInAt
) {
}
