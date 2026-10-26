package com.seatvault.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Records a gate check-in for one seat's e-ticket.
 *
 * <p>The unique constraint on {@code seat_id} is the replay guard: a scanner can
 * verify (and re-verify) the same QR as many times as it likes, but only the first
 * successful check-in persists a row — {@link com.seatvault.service.TicketService}
 * catches the resulting {@code DataIntegrityViolationException} on a duplicate
 * INSERT and reports "already checked in" instead of throwing.
 */
@Entity
@Table(
        name = "ticket_checkins",
        uniqueConstraints = @UniqueConstraint(name = "uq_ticket_checkins_seat", columnNames = "seat_id")
)
@Getter
@Setter
@NoArgsConstructor
public class TicketCheckin extends BaseEntity {

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    @Column(name = "checked_in_by", nullable = false, length = 255)
    private String checkedInBy;

    public TicketCheckin(Long bookingId, Long seatId, Instant checkedInAt, String checkedInBy) {
        this.bookingId = bookingId;
        this.seatId = seatId;
        this.checkedInAt = checkedInAt;
        this.checkedInBy = checkedInBy;
    }
}
