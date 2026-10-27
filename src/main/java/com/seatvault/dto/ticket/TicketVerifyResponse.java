package com.seatvault.dto.ticket;

import java.time.Instant;

/**
 * Result of a gate-scan. {@code alreadyCheckedIn} distinguishes a legitimate
 * re-scan (returns the original check-in time, no error) from a fresh one — a
 * scanner can safely re-verify the same QR without it being treated as fraud,
 * while the gate UI still shows it as "already used" and refuses entry.
 *
 * @param checkedInBy who recorded the (original) check-in, so staff can follow up on a re-used QR.
 */
public record TicketVerifyResponse(
        boolean valid,
        Long bookingId,
        Long seatId,
        String seatLabel,
        String eventTitle,
        boolean alreadyCheckedIn,
        Instant checkedInAt,
        String bookingReference,
        String tierName,
        String holderName,
        String checkedInBy
) {
}
