package com.seatvault.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A booking groups the seats a user is reserving/has reserved for one event.
 *
 * <p>Created in {@link BookingStatus#PENDING} at hold time with an {@code expiresAt}.
 * On successful payment it becomes {@link BookingStatus#CONFIRMED}; if the hold
 * lapses first, the scheduled job marks it {@link BookingStatus#EXPIRED} and
 * releases the seats. We store the seats' link to the booking on the Seat side
 * ({@code seat.booking_id}) rather than a JPA collection here, to avoid loading
 * all seats when we only need to flip a booking's status.
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_bookings_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_bookings_event"))
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status = BookingStatus.PENDING;

    /** Number of seats in this booking — kept for quick display/validation. */
    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    // ---- Frozen payable amount (minor units), captured at hold time so the price
    //      cannot drift between checkout and payment. ----

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    /** Total payable (seat prices + fees), in minor units. The gateway charges this. */
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    /** Portion of {@link #amountMinor} that is convenience fees (for display). */
    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    /** When the PENDING hold lapses. Null once CONFIRMED/EXPIRED/CANCELLED. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public Booking(User user, Event event, int seatCount, Instant expiresAt) {
        this.user = user;
        this.event = event;
        this.seatCount = seatCount;
        this.expiresAt = expiresAt;
        this.status = BookingStatus.PENDING;
    }

    /**
     * Full constructor that also freezes the payable amount at creation (hold) time.
     */
    public Booking(User user, Event event, int seatCount, Instant expiresAt,
                   String currency, long amountMinor, long feeMinor) {
        this(user, event, seatCount, expiresAt);
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.feeMinor = feeMinor;
    }

    public void confirm(Instant when) {
        this.status = BookingStatus.CONFIRMED;
        this.confirmedAt = when;
        this.expiresAt = null;
    }

    public void expire() {
        this.status = BookingStatus.EXPIRED;
        this.expiresAt = null;
    }

    public void cancel() {
        this.status = BookingStatus.CANCELLED;
        this.expiresAt = null;
    }

    public boolean isPending() {
        return status == BookingStatus.PENDING;
    }
}
