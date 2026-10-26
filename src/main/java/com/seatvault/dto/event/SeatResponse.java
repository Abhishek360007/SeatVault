package com.seatvault.dto.event;

import com.seatvault.domain.SeatStatus;

/**
 * A seat as exposed to clients. Note we never leak the optimistic-lock
 * {@code version} or the holder's user id to other users.
 *
 * <p>{@code tierName} and {@code priceMinor} are the seat's resolved tier and
 * effective price (minor units) so the seat-picker can show real prices from the
 * API instead of guessing them on the client.
 */
public record SeatResponse(
        Long id,
        String seatLabel,
        SeatStatus status,
        String tierName,
        long priceMinor
) {
}
