package com.seatvault.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A single seat for an event — the unit of contention in this system.
 *
 * <h3>Concurrency design (the core of the project)</h3>
 *
 * <p><b>Optimistic locking</b> via {@link Version}: every UPDATE carries the row's
 * version in its WHERE clause and bumps it. If two transactions both read version
 * {@code N} and try to hold the same seat, only the first UPDATE matches; the
 * second affects 0 rows and Hibernate throws {@code OptimisticLockException}. The
 * service layer (Phase 5) catches that and reports the seat as taken. This makes
 * overselling impossible even without table locks, and scales well because readers
 * never block.
 *
 * <p><b>Pessimistic locking</b> is also supported: the seat repository exposes a
 * {@code SELECT ... FOR UPDATE} finder (Phase 5). Pessimistic locking serializes
 * writers on the row up front (no retries needed) at the cost of holding a DB lock
 * for the transaction's duration. We implement and benchmark both paths and
 * discuss the trade-off there.
 *
 * <p>The hold metadata ({@code heldByUserId}, {@code holdExpiresAt}) lets the
 * scheduled job (Phase 6) find and release expired holds idempotently.
 */
@Entity
@Table(
        name = "seats",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_seats_event_label",
                columnNames = {"event_id", "seat_label"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class Seat extends BaseEntity {

    /**
     * Optimistic-lock version. Managed entirely by Hibernate; do not set manually.
     * Starts at 0 and increments on every successful UPDATE.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_seats_event"))
    private Event event;

    /** Human-facing label, unique within an event, e.g. "A1", "B12". */
    @Column(name = "seat_label", nullable = false, length = 16)
    private String seatLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeatStatus status = SeatStatus.AVAILABLE;

    /** Who currently holds (or booked) this seat; null when AVAILABLE. */
    @Column(name = "held_by_user_id")
    private Long heldByUserId;

    /** When the current HELD reservation lapses; null unless status == HELD. */
    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    /** The booking this seat belongs to once held/booked; null when AVAILABLE. */
    @Column(name = "booking_id")
    private Long bookingId;

    // ---- Optional per-seat pricing (tiering). NULL price → use event base price. ----

    /** Human-facing tier label, e.g. "Premium"/"Standard"; null when untiered. */
    @Column(name = "tier_name", length = 40)
    private String tierName;

    /** Seat price in minor units; null means "use the event's base price". */
    @Column(name = "price_minor")
    private Long priceMinor;

    public Seat(Event event, String seatLabel) {
        this.event = event;
        this.seatLabel = seatLabel;
        this.status = SeatStatus.AVAILABLE;
    }

    /**
     * Effective seat price in minor units: the seat's own tier price if set,
     * otherwise the event's base price. Resolved against the owning event.
     */
    public long effectivePriceMinor() {
        return priceMinor != null ? priceMinor : (event != null ? event.getBasePriceMinor() : 0L);
    }

    // ---- State-transition helpers (keep invariants in one place) ----

    public void hold(Long userId, Long bookingId, Instant expiresAt) {
        this.status = SeatStatus.HELD;
        this.heldByUserId = userId;
        this.bookingId = bookingId;
        this.holdExpiresAt = expiresAt;
    }

    public void confirmBooking() {
        this.status = SeatStatus.BOOKED;
        this.holdExpiresAt = null; // booked seats never expire
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.heldByUserId = null;
        this.holdExpiresAt = null;
        this.bookingId = null;
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }
}
