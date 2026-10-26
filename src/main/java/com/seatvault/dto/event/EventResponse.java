package com.seatvault.dto.event;

import java.time.Instant;

/**
 * Event summary for list/browse and create/update responses.
 *
 * <p>{@code availableSeats} is the live count of AVAILABLE seats. For the list
 * endpoint it is filled with a single grouped query for the whole page (no N+1);
 * see {@code EventService}.
 *
 * <p>Pricing fields ({@code currency}, {@code basePriceMinor},
 * {@code convenienceFeeMinor}) let the client render prices from API data rather
 * than hardcoding them. Money is in minor units.
 */
public record EventResponse(
        Long id,
        String title,
        String description,
        String venue,
        Instant eventDateTime,
        int totalCapacity,
        long availableSeats,
        String currency,
        long basePriceMinor,
        long convenienceFeeMinor,
        boolean highDemand,
        Instant createdAt
) {
}
