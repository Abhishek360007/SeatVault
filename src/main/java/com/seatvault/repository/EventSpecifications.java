package com.seatvault.repository;

import com.seatvault.domain.Event;
import com.seatvault.domain.EventCategory;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.dto.event.EventSearchCriteria;
import com.seatvault.dto.event.EventSort;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Composable JPA Specifications for the public event search.
 *
 * <p>Each filter returns {@code null} when its input is absent, and Spring Data
 * skips {@code null} specifications, so only the filters the caller actually
 * supplied reach the SQL. Patterns are only built from non-blank input, so
 * PostgreSQL never sees an untyped null bind inside {@code lower(...)}.
 */
public final class EventSpecifications {

    private EventSpecifications() {
    }

    /** All filters in {@code c}, plus the ORDER BY when the sort needs a subquery. */
    public static Specification<Event> from(EventSearchCriteria c) {
        return Specification.where(keyword(c.keyword()))
                .and(category(c.category()))
                .and(city(c.city()))
                .and(venue(c.venue()))
                .and(startsAtOrAfter(c.fromDate()))
                .and(startsAtOrBefore(c.toDate()))
                .and(minPrice(c.minPriceMinor()))
                .and(maxPrice(c.maxPriceMinor()))
                .and(c.availableOnly() ? hasAvailableSeats() : null)
                .and(c.sort() == EventSort.POPULARITY ? orderByPopularity(c.descending()) : null);
    }

    public static Specification<Event> keyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String pattern = "%" + keyword.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern),
                cb.like(cb.lower(root.get("venue")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("city"), "")), pattern));
    }

    public static Specification<Event> category(EventCategory category) {
        return category == null ? null : (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    /** Case-insensitive exact match; served by idx_events_city_lower. */
    public static Specification<Event> city(String city) {
        if (!StringUtils.hasText(city)) {
            return null;
        }
        String normalized = city.trim().toLowerCase();
        return (root, query, cb) -> cb.equal(cb.lower(root.get("city")), normalized);
    }

    public static Specification<Event> venue(String venue) {
        if (!StringUtils.hasText(venue)) {
            return null;
        }
        String pattern = "%" + venue.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("venue")), pattern);
    }

    public static Specification<Event> startsAtOrAfter(Instant from) {
        return from == null ? null : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("eventDateTime"), from);
    }

    public static Specification<Event> startsAtOrBefore(Instant to) {
        return to == null ? null : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("eventDateTime"), to);
    }

    public static Specification<Event> minPrice(Long min) {
        return min == null ? null : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("basePriceMinor"), min);
    }

    public static Specification<Event> maxPrice(Long max) {
        return max == null ? null : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("basePriceMinor"), max);
    }

    /** EXISTS an AVAILABLE seat; served by idx_seats_event_status. */
    public static Specification<Event> hasAvailableSeats() {
        return (root, query, cb) -> {
            Subquery<Long> sq = query.subquery(Long.class);
            Root<Seat> seat = sq.from(Seat.class);
            sq.select(cb.literal(1L)).where(
                    cb.equal(seat.get("event"), root),
                    cb.equal(seat.get("status"), SeatStatus.AVAILABLE));
            return cb.exists(sq);
        };
    }

    /**
     * Orders by seats taken (HELD or BOOKED), then date and id so page boundaries
     * are stable. Adds no predicate, and skips the count query (result type
     * {@code Long}) where ordering is meaningless. Callers must pass an unsorted
     * {@code Pageable}, otherwise Spring Data replaces this ORDER BY with its own.
     */
    public static Specification<Event> orderByPopularity(boolean descending) {
        return (root, query, cb) -> {
            if (Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType())) {
                return null;
            }
            Subquery<Long> taken = query.subquery(Long.class);
            Root<Seat> seat = taken.from(Seat.class);
            taken.select(cb.count(seat)).where(
                    cb.equal(seat.get("event"), root),
                    cb.notEqual(seat.get("status"), SeatStatus.AVAILABLE));
            query.orderBy(
                    descending ? cb.desc(taken) : cb.asc(taken),
                    cb.asc(root.get("eventDateTime")),
                    cb.asc(root.get("id")));
            return null;
        };
    }
}
