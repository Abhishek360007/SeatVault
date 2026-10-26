package com.seatvault.dto.booking;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request to place a temporary hold on specific seats of an event.
 *
 * @param eventId        the event the seats belong to.
 * @param seatIds        the seat ids to hold (deduplicated by the service). Must be
 *                       non-empty and every element must be a positive, non-null id.
 * @param admissionToken required only for high-demand events (see {@code Event#highDemand}):
 *                       the short-lived token returned by {@code GET /queue/status} once
 *                       admitted from the waiting room. Ignored for normal events.
 */
public record HoldRequest(

        @NotNull(message = "eventId is required")
        @Positive(message = "eventId must be positive")
        Long eventId,

        @NotEmpty(message = "seatIds must contain at least one seat")
        List<@NotNull(message = "seatId must not be null") @Positive(message = "seatId must be positive") Long> seatIds,

        @Size(max = 200, message = "admissionToken is too long")
        String admissionToken
) {
    public HoldRequest(Long eventId, List<Long> seatIds) {
        this(eventId, seatIds, null);
    }
}
