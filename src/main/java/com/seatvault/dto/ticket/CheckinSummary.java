package com.seatvault.dto.ticket;

/** Gate progress for one event: tickets sold (booked seats) and how many have been admitted. */
public record CheckinSummary(Long eventId, String eventTitle, long ticketsSold, long checkedIn) {
}
