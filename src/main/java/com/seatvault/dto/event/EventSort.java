package com.seatvault.dto.event;

/**
 * Sort keys for the public event listing.
 *
 * <ul>
 *   <li>{@code DATE} — event start time.</li>
 *   <li>{@code PRICE} — base seat price (the "from" price shown on cards).</li>
 *   <li>{@code POPULARITY} — seats already held or booked (higher = more popular).</li>
 * </ul>
 */
public enum EventSort {
    DATE,
    PRICE,
    POPULARITY
}
