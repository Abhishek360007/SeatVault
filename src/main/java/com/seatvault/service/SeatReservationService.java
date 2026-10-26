package com.seatvault.service;

import com.seatvault.domain.Booking;
import com.seatvault.domain.Event;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.domain.User;
import com.seatvault.dto.booking.BookingResponse;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.exception.SeatUnavailableException;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The transactional heart of the system: it turns AVAILABLE seats into a HELD
 * booking, and a HELD booking into a CONFIRMED one, <b>without ever overselling</b>.
 *
 * <h2>Why a separate bean from BookingService?</h2>
 * Each method here is its own transaction (via the Spring proxy). The optimistic
 * retry loop lives in {@link BookingService} and calls {@link #reserveOptimistic}
 * repeatedly — each call must be a <em>fresh</em> transaction, which only works
 * across a proxy boundary (self-invocation would not start a new transaction).
 *
 * <h2>Isolation level</h2>
 * We use READ_COMMITTED (PostgreSQL's default). It is sufficient for both paths:
 * <ul>
 *   <li><b>Pessimistic</b>: {@code SELECT ... FOR UPDATE} takes row locks, so a
 *       concurrent transaction blocks until we commit and then re-reads the new
 *       (committed) state — no lost update is possible even at READ_COMMITTED.</li>
 *   <li><b>Optimistic</b>: the {@code @Version} check detects any concurrent
 *       modification at flush time and fails the loser.</li>
 * </ul>
 * SERIALIZABLE would also work but adds serialization-failure retries and overhead
 * we don't need, since the invariant ("a seat is held by at most one booking") is
 * already protected at the row level.
 *
 * <h2>Cache invalidation (Phase 7)</h2>
 * Every successful state change alters the event's availability, so we evict the
 * event's cached reads via {@link CacheInvalidator} (deferred to after-commit).
 */
@Service
public class SeatReservationService {

    private static final Logger log = LoggerFactory.getLogger(SeatReservationService.class);

    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;
    private final CacheInvalidator cacheInvalidator;

    public SeatReservationService(SeatRepository seatRepository,
                                  BookingRepository bookingRepository,
                                  EventRepository eventRepository,
                                  CacheInvalidator cacheInvalidator) {
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
        this.cacheInvalidator = cacheInvalidator;
    }

    // ============================================================
    //  HOLD — pessimistic path
    // ============================================================

    /**
     * Holds seats using row-level locks.
     *
     * <p>{@code findForUpdate} issues {@code SELECT ... FOR UPDATE} ordered by id,
     * so concurrent holders of any overlapping seat block here until we commit; the
     * deterministic order prevents deadlocks between multi-seat bookings. Once we
     * hold the locks, no other transaction can have changed these rows, so the
     * AVAILABLE check and the transition to HELD are atomic. <b>This is the
     * recommended default for hot single-seat contention: exactly one winner, the
     * rest block briefly and then see the seat is taken — no wasted retries.</b>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse reservePessimistic(User user, Long eventId, List<Long> seatIds, Instant expiresAt) {
        Event event = loadEvent(eventId);
        List<Seat> seats = seatRepository.findForUpdate(eventId, seatIds); // locks rows
        return hold(user, event, seats, seatIds, expiresAt);
    }

    // ============================================================
    //  HOLD — optimistic path
    // ============================================================

    /**
     * Holds seats without locking. Reads the rows, transitions AVAILABLE→HELD, and
     * relies on the {@code @Version} column: at flush/commit Hibernate issues
     * {@code UPDATE ... WHERE id=? AND version=?} for each seat. If a concurrent
     * transaction already bumped the version, our UPDATE matches 0 rows and
     * Hibernate throws {@code OptimisticLockException}
     * ({@code ObjectOptimisticLockingFailureException}), which the caller's retry
     * loop handles. Readers never block — great when contention is low; costs
     * retries when it's high.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse reserveOptimistic(User user, Long eventId, List<Long> seatIds, Instant expiresAt) {
        Event event = loadEvent(eventId);
        List<Seat> seats = seatRepository.findAllForBooking(eventId, seatIds); // no lock
        return hold(user, event, seats, seatIds, expiresAt);
    }

    /** Shared hold logic for both strategies. */
    private BookingResponse hold(User user, Event event, List<Seat> seats, List<Long> seatIds, Instant expiresAt) {
        validateAllPresent(seats, seatIds);
        validateAllAvailable(seats);

        // Freeze the payable amount NOW (hold time) so checkout can't be repriced.
        long seatTotal = seats.stream().mapToLong(Seat::effectivePriceMinor).sum();
        long feeTotal = (long) seats.size() * event.getConvenienceFeeMinor();
        Booking booking = new Booking(user, event, seats.size(), expiresAt,
                event.getCurrency(), seatTotal + feeTotal, feeTotal);
        booking = bookingRepository.save(booking); // flush to obtain id for seat.bookingId

        for (Seat seat : seats) {
            seat.hold(user.getId(), booking.getId(), expiresAt);
        }
        // Dirty-checked seats are flushed on commit; optimistic version check fires here.
        seatRepository.saveAll(seats);
        List<com.seatvault.dto.booking.SeatStatusUpdate> updates = seats.stream()
                .map(s -> new com.seatvault.dto.booking.SeatStatusUpdate(s.getId(), "HELD", user.getId()))
                .toList();
        cacheInvalidator.evictEventAvailability(event.getId(), updates);
        log.info("Held {} seat(s) for user={} event={} booking={}",
                seats.size(), user.getId(), event.getId(), booking.getId());
        return BookingResponse.of(booking, seats);
    }

    // ============================================================
    //  CONFIRM — after a successful (external) payment
    // ============================================================

    /**
     * Flips a paid booking's seats HELD→BOOKED. Called AFTER the payment provider
     * approved (the charge happens outside any transaction/lock). We re-lock the
     * seats with {@code FOR UPDATE} and re-validate, because the hold could have
     * expired between validation and payment; in that case we refuse and the caller
     * surfaces it (a real system would also trigger a refund).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse confirm(Long bookingId, Long userId, Instant now) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
        requireOwner(booking, userId);

        if (!booking.isPending()) {
            throw new ConflictException("Booking is not pending (status=" + booking.getStatus() + ")");
        }
        if (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(now)) {
            throw new ConflictException("Hold has expired; seats were released");
        }

        List<Long> seatIds = seatRepository.findByBookingId(bookingId).stream().map(Seat::getId).toList();
        List<Seat> seats = seatRepository.findForUpdate(booking.getEvent().getId(), seatIds); // re-lock
        for (Seat seat : seats) {
            if (seat.getStatus() != SeatStatus.HELD || !bookingId.equals(seat.getBookingId())) {
                throw new ConflictException("A held seat changed state; cannot confirm");
            }
            seat.confirmBooking();
        }
        booking.confirm(now);
        seatRepository.saveAll(seats);
        List<com.seatvault.dto.booking.SeatStatusUpdate> updates = seats.stream()
                .map(s -> new com.seatvault.dto.booking.SeatStatusUpdate(s.getId(), "BOOKED", null))
                .toList();
        cacheInvalidator.evictEventAvailability(booking.getEvent().getId(), updates);
        log.info("Confirmed booking={} ({} seats) for user={}", bookingId, seats.size(), userId);
        return BookingResponse.of(booking, seats);
    }

    // ============================================================
    //  CANCEL — user releases a pending hold early
    // ============================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse cancel(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
        requireOwner(booking, userId);
        if (!booking.isPending()) {
            throw new ConflictException("Only pending bookings can be cancelled (status=" + booking.getStatus() + ")");
        }
        List<Long> seatIds = seatRepository.findByBookingId(bookingId).stream().map(Seat::getId).toList();
        List<Seat> seats = seatRepository.findForUpdate(booking.getEvent().getId(), seatIds);
        for (Seat seat : seats) {
            seat.release();
        }
        booking.cancel();
        seatRepository.saveAll(seats);
        List<com.seatvault.dto.booking.SeatStatusUpdate> updates = seats.stream()
                .map(s -> new com.seatvault.dto.booking.SeatStatusUpdate(s.getId(), "AVAILABLE", null))
                .toList();
        cacheInvalidator.evictEventAvailability(booking.getEvent().getId(), updates);
        log.info("Cancelled booking={} for user={}, released {} seats", bookingId, userId, seats.size());
        return BookingResponse.of(booking, seats);
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private Event loadEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    }

    private void validateAllPresent(List<Seat> seats, List<Long> requestedIds) {
        if (seats.size() != requestedIds.size()) {
            throw new ResourceNotFoundException(
                    "One or more seats do not exist for this event: requested=" + requestedIds.size()
                            + " found=" + seats.size());
        }
    }

    private void validateAllAvailable(List<Seat> seats) {
        List<String> taken = seats.stream()
                .filter(s -> !s.isAvailable())
                .map(Seat::getSeatLabel)
                .toList();
        if (!taken.isEmpty()) {
            throw new SeatUnavailableException("Seats no longer available: " + String.join(", ", taken));
        }
    }

    private void requireOwner(Booking booking, Long userId) {
        if (!booking.getUser().getId().equals(userId)) {
            // 404 rather than 403 so we don't reveal that someone else's booking exists.
            throw new ResourceNotFoundException("Booking not found");
        }
    }
}
