package com.seatvault.repository.projection;

/**
 * Spring Data interface projection for the per-event available-seat count,
 * computed in one grouped query across a whole page of events.
 */
public interface EventAvailability {
    Long getEventId();

    long getAvailableCount();
}
