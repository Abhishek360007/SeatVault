package com.seatvault.dto.event;

import com.seatvault.domain.EventCategory;

import java.time.Instant;

/**
 * Filters and sort for a public event search. Every filter is optional; a
 * {@code null} field means "don't filter on this".
 *
 * <p>Being a record, it has value-based {@code equals}/{@code hashCode}, which the
 * search cache key relies on.
 *
 * @param keyword       free-text match on title, description, venue or city.
 * @param category      exact category.
 * @param city          case-insensitive exact city.
 * @param venue         case-insensitive partial venue match.
 * @param fromDate      events at/after this instant.
 * @param toDate        events at/before this instant.
 * @param minPriceMinor base seat price at least this (minor units).
 * @param maxPriceMinor base seat price at most this (minor units).
 * @param availableOnly only events with at least one AVAILABLE seat.
 * @param sort          sort key; defaults to {@link EventSort#DATE}.
 * @param descending    sort direction; defaults to ascending.
 */
public record EventSearchCriteria(
        String keyword,
        EventCategory category,
        String city,
        String venue,
        Instant fromDate,
        Instant toDate,
        Long minPriceMinor,
        Long maxPriceMinor,
        boolean availableOnly,
        EventSort sort,
        boolean descending
) {
    public EventSearchCriteria {
        if (sort == null) {
            sort = EventSort.DATE;
        }
    }

    /** Keyword/venue/date-only search, as the listing supported before the richer filters. */
    public static EventSearchCriteria basic(String keyword, String venue, Instant fromDate) {
        return new EventSearchCriteria(keyword, null, null, venue, fromDate, null,
                null, null, false, EventSort.DATE, false);
    }
}
