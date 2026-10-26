package com.seatvault.dto.queue;

/**
 * Response to {@code POST /queue/join} and {@code GET /queue/status}.
 *
 * @param eventId         the event this status is for.
 * @param admitted        true once the caller may hold seats.
 * @param position        1-based position in the FIFO wait line; null once admitted
 *                        or when the event isn't high-demand (no queue to wait in).
 * @param admissionToken  present only when {@code admitted} — pass this as
 *                        {@code HoldRequest.admissionToken} to {@code /bookings/hold}.
 * @param tokenExpiresInSeconds remaining validity of {@code admissionToken}; poll
 *                        {@code /queue/status} again before it lapses to get a fresh one.
 */
public record QueueStatusResponse(
        Long eventId,
        boolean admitted,
        Long position,
        String admissionToken,
        Long tokenExpiresInSeconds
) {
}
