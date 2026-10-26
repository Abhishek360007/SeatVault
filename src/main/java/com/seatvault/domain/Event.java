package com.seatvault.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An event with a fixed set of seats.
 *
 * <p>{@code totalCapacity} is denormalized for convenience but the authoritative
 * capacity is simply {@code seats.size()}; a CHECK/trigger could enforce equality,
 * but we keep capacity as the seed count used when generating seats (Phase 4).
 *
 * <p>The {@code seats} collection is mapped for navigation and cascade-creation
 * of seats when an event is created. We deliberately do NOT rely on this
 * collection for booking — seat mutations go through the seat repository with
 * row-level locking so we never load the whole collection into memory under
 * contention (Phase 5).
 */
@Entity
@Table(name = "events")
@org.hibernate.annotations.SQLDelete(sql = "UPDATE events SET deleted = true WHERE id = ?")
@org.hibernate.annotations.SQLRestriction("deleted = false")
@jakarta.persistence.EntityListeners(EventAuditListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Event extends BaseEntity {

    @Column(nullable = false)
    private boolean deleted = false;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private String venue;

    @Column(name = "event_datetime", nullable = false)
    private Instant eventDateTime;

    @Column(name = "total_capacity", nullable = false)
    private int totalCapacity;

    // ---- Pricing (Phase: pricing model). Money is stored in MINOR units. ----

    /** ISO-4217 currency code, e.g. INR/EUR/USD. Defaults to INR. */
    @Column(nullable = false, length = 3)
    private String currency = "INR";

    /** Default seat price (minor units) used when a seat has no tier price of its own. */
    @Column(name = "base_price_minor", nullable = false)
    private long basePriceMinor;

    /** Flat per-seat convenience fee (minor units) added on top of the seat price. */
    @Column(name = "convenience_fee_minor", nullable = false)
    private long convenienceFeeMinor;

    /**
     * When true, seat holds for this event must go through the virtual waiting room
     * (see {@code QueueService}): a caller needs a valid, short-lived admission token
     * from {@code /queue/status} before {@code /bookings/hold} will accept them.
     */
    @Column(name = "high_demand", nullable = false)
    private boolean highDemand = false;

    /**
     * Cascade so creating an Event with its seats persists both. {@code orphanRemoval}
     * lets seat regeneration work if an event is rebuilt. {@code mappedBy} means Seat
     * owns the FK.
     */
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Seat> seats = new ArrayList<>();

    public Event(String title, String description, String venue, Instant eventDateTime, int totalCapacity) {
        this.title = title;
        this.description = description;
        this.venue = venue;
        this.eventDateTime = eventDateTime;
        this.totalCapacity = totalCapacity;
    }

    /** Convenience to keep both sides of the relationship in sync. */
    public void addSeat(Seat seat) {
        seats.add(seat);
        seat.setEvent(this);
    }
}
