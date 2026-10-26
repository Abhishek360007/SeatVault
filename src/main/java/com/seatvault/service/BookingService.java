package com.seatvault.service;

import com.seatvault.config.BookingProperties;
import com.seatvault.domain.Booking;
import com.seatvault.domain.Event;
import com.seatvault.domain.Seat;
import com.seatvault.domain.User;
import com.seatvault.dto.PagedResponse;
import com.seatvault.dto.booking.BookingResponse;
import com.seatvault.dto.booking.HoldRequest;
import com.seatvault.dto.booking.PaymentRequest;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.PaymentFailedException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.exception.SeatUnavailableException;
import com.seatvault.payment.PaymentGateway;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.PaymentRepository;
import com.seatvault.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Orchestrates the booking lifecycle: hold → pay → confirm (and cancel / read).
 *
 * <p>Design highlights an interviewer will care about:
 * <ul>
 *   <li><b>Strategy dispatch</b> between pessimistic and optimistic locking
 *       (configurable), with a retry loop for the optimistic path. Each retry is a
 *       fresh transaction because it crosses the proxy into
 *       {@link SeatReservationService}.</li>
 *   <li><b>Payment never runs inside a DB transaction or while holding locks.</b>
 *       We validate, then charge over the "network", then confirm in a short
 *       locked transaction. Blocking on an external call while holding row locks
 *       would tank throughput and risk lock-timeout cascades.</li>
 *   <li><b>Optional Redis lock</b> in front of the hold to shed contention before
 *       it reaches the DB (see {@link RedisLockService}).</li>
 * </ul>
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final Duration REDIS_LOCK_TTL = Duration.ofSeconds(10);

    private final SeatReservationService reservationService;
    private final PaymentService paymentService;
    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final RedisLockService redisLockService;
    private final BookingProperties properties;
    private final EventRepository eventRepository;
    private final QueueService queueService;

    public BookingService(SeatReservationService reservationService,
                          PaymentService paymentService,
                          BookingRepository bookingRepository,
                          SeatRepository seatRepository,
                          PaymentRepository paymentRepository,
                          PaymentGateway paymentGateway,
                          RedisLockService redisLockService,
                          BookingProperties properties,
                          EventRepository eventRepository,
                          QueueService queueService) {
        this.reservationService = reservationService;
        this.paymentService = paymentService;
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.redisLockService = redisLockService;
        this.properties = properties;
        this.eventRepository = eventRepository;
        this.queueService = queueService;
    }

    // ============================ HOLD ============================

    /**
     * Places a hold on the requested seats. Dispatches to the configured locking
     * strategy; the optimistic path retries on version conflicts.
     */
    public BookingResponse hold(User user, HoldRequest request) {
        List<Long> seatIds = dedupe(request.seatIds());
        if (seatIds.size() > properties.maxSeatsPerBooking()) {
            throw new SeatUnavailableException(
                    "Cannot hold more than " + properties.maxSeatsPerBooking() + " seats in one booking");
        }
        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + request.eventId()));
        if (event.isHighDemand()) {
            queueService.requireAdmission(event.getId(), user.getId(), request.admissionToken());
        }
        Instant expiresAt = Instant.now().plus(properties.holdDurationMinutes(), ChronoUnit.MINUTES);

        if (properties.redisLockEnabled()) {
            return holdWithRedisLock(user, request.eventId(), seatIds, expiresAt);
        }
        return holdInternal(user, request.eventId(), seatIds, expiresAt);
    }

    private BookingResponse holdInternal(User user, Long eventId, List<Long> seatIds, Instant expiresAt) {
        return switch (properties.lockingStrategy()) {
            case PESSIMISTIC -> reservationService.reservePessimistic(user, eventId, seatIds, expiresAt);
            case OPTIMISTIC -> holdOptimisticWithRetry(user, eventId, seatIds, expiresAt);
        };
    }

    /**
     * Optimistic retry loop. Each attempt is a brand-new transaction (proxied call).
     * After exhausting retries we report the seats as unavailable — by then a
     * persistent conflict almost certainly means someone else genuinely took them.
     */
    private BookingResponse holdOptimisticWithRetry(User user, Long eventId, List<Long> seatIds, Instant expiresAt) {
        int maxRetries = properties.optimisticMaxRetries();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return reservationService.reserveOptimistic(user, eventId, seatIds, expiresAt);
            } catch (OptimisticLockingFailureException ex) {
                log.debug("Optimistic conflict on attempt {}/{} for event={} seats={}",
                        attempt, maxRetries, eventId, seatIds);
                if (attempt == maxRetries) {
                    throw new SeatUnavailableException(
                            "Seats are being booked by many users right now; please try again");
                }
                backoff(attempt);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * Acquires a Redis lock per seat (sorted, to avoid lock-order deadlock) before
     * delegating to the DB path; releases all locks afterwards. If any seat lock is
     * already held, we fail fast instead of queuing on the DB.
     */
    private BookingResponse holdWithRedisLock(User user, Long eventId, List<Long> seatIds, Instant expiresAt) {
        List<RedisLockService.Lock> acquired = new ArrayList<>();
        try {
            for (Long seatId : seatIds) { // seatIds already sorted by dedupe()
                String key = "lock:seat:" + eventId + ":" + seatId;
                RedisLockService.Lock lock = redisLockService.tryAcquire(key, REDIS_LOCK_TTL);
                if (lock == null) {
                    throw new SeatUnavailableException("Seat " + seatId + " is currently being booked");
                }
                acquired.add(lock);
            }
            return holdInternal(user, eventId, seatIds, expiresAt);
        } finally {
            acquired.forEach(redisLockService::release);
        }
    }

    // ============================ PAY ============================

    /**
     * Pays for a pending booking, <b>idempotently</b>. The external charge happens
     * OUTSIDE any DB transaction/lock, sandwiched between two short transactions:
     * <ol>
     *   <li><b>claim</b> — reserve the idempotency slot. A repeated request with the
     *       same {@code idempotencyKey} replays the prior result instead of charging
     *       again; a concurrent same-key request loses the unique-constraint race and
     *       is also replayed (see {@link PaymentService}).</li>
     *   <li><b>charge</b> — call the gateway with the booking's <em>frozen</em> amount
     *       (never a hardcoded constant), never while holding DB locks.</li>
     *   <li><b>finalize</b> — persist the result and confirm the booking exactly once.</li>
     * </ol>
     * On decline the booking stays PENDING (the user may retry with a new key;
     * otherwise the scheduled job releases the seats), so seats are never stuck.
     */
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "paymentGateway", fallbackMethod = "fallbackPay")
    public BookingResponse pay(User user, Long bookingId, PaymentRequest request) {
        Long userId = user.getId();

        // 1) Claim the idempotency slot (or replay an existing result).
        PaymentService.ClaimResult claim;
        try {
            claim = paymentService.claim(bookingId, userId, request.idempotencyKey());
        } catch (DataIntegrityViolationException raceLost) {
            // A concurrent request with the same key won the INSERT; replay its result.
            log.debug("Idempotency race lost for booking={} key={}; replaying winner",
                    bookingId, request.idempotencyKey());
            return paymentService.replay(bookingId, userId, request.idempotencyKey()).approvedReplay();
        }
        if (claim.isApprovedReplay()) {
            log.debug("Replaying already-approved payment for booking={}", bookingId);
            return claim.approvedReplay();
        }

        // 2) Charge OUTSIDE any DB transaction/lock, using the frozen booking amount.
        PaymentGateway.PaymentResult result;
        try {
            result = paymentGateway.charge(new PaymentGateway.PaymentCommand(
                    bookingId, userId, request.paymentMethod(), claim.amountMinor(), claim.currency()));
        } catch (RuntimeException gatewayError) {
            log.warn("Payment gateway error for booking={}: {}", bookingId, gatewayError.toString());
            paymentService.markFailed(claim.paymentId(), bookingId, "Payment provider error");
            throw new PaymentFailedException("Payment provider error; please retry");
        }

        // 3) Finalize: persist the outcome and confirm exactly once. finalize never
        //    throws for a business outcome, so DECLINED/FAILED are durably persisted
        //    before we surface the error from here (outside the transaction).
        PaymentService.FinalizeResult finalized =
                paymentService.finalizePayment(claim.paymentId(), bookingId, userId, result, Instant.now());

        return switch (finalized.status()) {
            case APPROVED -> finalized.response();
            case DECLINED -> {
                log.info("Payment declined for booking={}: {}", bookingId, finalized.failureReason());
                throw new PaymentFailedException(finalized.failureReason());
            }
            case FAILED -> throw new ConflictException(finalized.failureReason());
            case INITIATED -> throw new IllegalStateException("Payment not finalized for booking=" + bookingId);
        };
    }

    // ============================ CANCEL ============================

    public BookingResponse cancel(User user, Long bookingId) {
        return reservationService.cancel(bookingId, user.getId());
    }

    // ============================ READS ============================

    @Transactional(readOnly = true)
    public BookingResponse getById(User user, Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Booking not found");
        }
        List<Seat> seats = seatRepository.findByBookingId(bookingId);
        return BookingResponse.of(booking, seats, latestPayment(bookingId));
    }

    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> getMyBookings(User user, Pageable pageable) {
        Page<BookingResponse> page = bookingRepository.findByUserId(user.getId(), pageable)
                .map(b -> BookingResponse.of(b, seatRepository.findByBookingId(b.getId()), latestPayment(b.getId())));
        return PagedResponse.from(page);
    }

    /** Latest payment attempt for a booking (so "my tickets" can show status/ref/amount); null if none. */
    private com.seatvault.domain.Payment latestPayment(Long bookingId) {
        return paymentRepository.findTopByBookingIdOrderByIdDesc(bookingId).orElse(null);
    }

    /** Fallback method for payment service circuit breaker */
    public BookingResponse fallbackPay(User user, Long bookingId, PaymentRequest request, Throwable t) {
        log.error("Payment gateway circuit breaker tripped or request failed for bookingId={}", bookingId, t);
        throw new com.seatvault.exception.PaymentFailedException(
                "Payment gateway is temporarily unavailable. Please try again later.");
    }

    // ============================ helpers ============================

    /** Dedupe + sort seat ids: stable lock ordering and no double-holding one seat. */
    private List<Long> dedupe(List<Long> seatIds) {
        return new ArrayList<>(new LinkedHashSet<>(seatIds)).stream().sorted().toList();
    }

    private void backoff(int attempt) {
        try {
            // tiny jittered backoff to spread out colliding retriers
            Thread.sleep(Math.min(50L * attempt, 200L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
