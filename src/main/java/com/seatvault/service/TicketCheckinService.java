package com.seatvault.service;

import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.domain.Event;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.domain.TicketCheckin;
import com.seatvault.dto.ticket.CheckinSummary;
import com.seatvault.dto.ticket.TicketVerifyResponse;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.repository.TicketCheckinRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Transactional persistence for gate check-ins, split from {@link TicketService}
 * for the same reason {@code PaymentService} is split from {@code BookingService}:
 * the unique-constraint race needs two <b>separate</b> transactions — an attempt
 * that may fail, and a fresh read after — and that only happens when each is
 * invoked across the Spring proxy from a different bean (self-invocation within
 * one class wouldn't start a new transaction for the second call).
 *
 * <p><b>Why this matters here specifically:</b> on PostgreSQL, a unique-violation
 * from {@code saveAndFlush} aborts the whole transaction — every subsequent
 * statement in it fails with "current transaction is aborted" until rollback. So
 * {@link TicketService#verifyAndCheckIn} (the non-transactional orchestrator) must
 * catch the violation and call {@link #replayCheckIn} as a brand-new transaction,
 * never read the existing row from inside the failed one.
 */
@Service
public class TicketCheckinService {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final TicketCheckinRepository ticketCheckinRepository;
    private final EventRepository eventRepository;

    public TicketCheckinService(BookingRepository bookingRepository,
                                SeatRepository seatRepository,
                                TicketCheckinRepository ticketCheckinRepository,
                                EventRepository eventRepository) {
        this.eventRepository = eventRepository;
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.ticketCheckinRepository = ticketCheckinRepository;
    }

    /**
     * Validates booking/seat state and records the check-in. On a duplicate scan the
     * unique constraint on {@code seat_id} makes {@code saveAndFlush} throw
     * {@code DataIntegrityViolationException} immediately — propagated to the caller,
     * NOT swallowed here, so the transaction rolls back cleanly instead of leaving a
     * poisoned one behind.
     */
    @Transactional
    public TicketVerifyResponse attemptCheckIn(TicketPayload payload, String scannerEmail) {
        Booking booking = loadBooking(payload.bookingId());
        Seat seat = loadSeat(payload.seatId());
        validateCheckinable(booking, seat);

        Instant now = Instant.now();
        ticketCheckinRepository.saveAndFlush(new TicketCheckin(booking.getId(), seat.getId(), now, scannerEmail));
        return response(booking, seat, false, now, scannerEmail);
    }

    /** Re-reads the existing check-in after losing the race in {@link #attemptCheckIn}. Read-only. */
    @Transactional(readOnly = true)
    public TicketVerifyResponse replayCheckIn(TicketPayload payload) {
        Booking booking = loadBooking(payload.bookingId());
        Seat seat = loadSeat(payload.seatId());
        TicketCheckin existing = ticketCheckinRepository.findBySeatId(seat.getId())
                .orElseThrow(() -> new ConflictException("Check-in race detected; please retry the scan"));
        return response(booking, seat, true, existing.getCheckedInAt(), existing.getCheckedInBy());
    }

    /** Tickets sold vs. admitted so far, for the gate screen. */
    @Transactional(readOnly = true)
    public CheckinSummary summary(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
        long sold = seatRepository.countByEventIdAndStatus(eventId, SeatStatus.BOOKED);
        long admitted = ticketCheckinRepository.countByEventId(eventId);
        return new CheckinSummary(eventId, event.getTitle(), sold, admitted);
    }

    private void validateCheckinable(Booking booking, Seat seat) {
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ConflictException("This booking is not confirmed; ticket is not valid for check-in");
        }
        if (seat.getStatus() != SeatStatus.BOOKED || !booking.getId().equals(seat.getBookingId())) {
            throw new ConflictException("This seat does not belong to a confirmed booking; ticket is not valid");
        }
    }

    private Booking loadBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket refers to a booking that no longer exists"));
    }

    private Seat loadSeat(Long seatId) {
        return seatRepository.findById(seatId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket refers to a seat that no longer exists"));
    }

    private TicketVerifyResponse response(Booking booking, Seat seat, boolean alreadyCheckedIn,
                                          Instant checkedInAt, String checkedInBy) {
        return new TicketVerifyResponse(true, booking.getId(), seat.getId(), seat.getSeatLabel(),
                booking.getEvent().getTitle(), alreadyCheckedIn, checkedInAt,
                booking.getReference(), seat.getTierName(), booking.getUser().getFullName(), checkedInBy);
    }
}
