package com.seatvault.service;

import com.seatvault.domain.Booking;
import com.seatvault.domain.Payment;
import com.seatvault.domain.PaymentStatus;
import com.seatvault.domain.Seat;
import com.seatvault.dto.booking.BookingResponse;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.PaymentFailedException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.payment.PaymentGateway;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.PaymentRepository;
import com.seatvault.repository.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Transactional persistence + idempotency for the payment lifecycle.
 *
 * <h2>Why a separate bean from {@link BookingService}?</h2>
 * Same reason as {@link SeatReservationService}: {@code BookingService} is the
 * non-transactional orchestrator that performs the external charge OUTSIDE any DB
 * transaction. The two short transactions around that charge — <b>claim</b> (reserve
 * the idempotency slot) and <b>finalize</b> (record the result + confirm exactly
 * once) — must be real transactions, which only happens when invoked across the
 * Spring proxy from another bean.
 *
 * <h2>Exactly-once charging</h2>
 * The unique {@code (booking_id, idempotency_key)} constraint is the serialization
 * point. Two concurrent {@code /pay} calls with the same key both try to INSERT an
 * INITIATED row; exactly one wins, the other gets a
 * {@code DataIntegrityViolationException} which the orchestrator turns into a replay
 * of the winner's result. So the gateway is charged at most once per key, and the
 * booking is confirmed at most once (also guarded by
 * {@link SeatReservationService#confirm}).
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final SeatReservationService reservationService;

    public PaymentService(PaymentRepository paymentRepository,
                          BookingRepository bookingRepository,
                          SeatRepository seatRepository,
                          SeatReservationService reservationService) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.reservationService = reservationService;
    }

    // ======================================================================
    //  Result types returned to the orchestrator
    // ======================================================================

    /** Outcome of {@link #claim}: either replay an already-APPROVED payment, or a fresh slot to charge. */
    public record ClaimResult(BookingResponse approvedReplay, Long paymentId, long amountMinor,
                              String currency) {
        public boolean isApprovedReplay() {
            return approvedReplay != null;
        }
        static ClaimResult replay(BookingResponse response) {
            return new ClaimResult(response, null, 0L, null);
        }
        static ClaimResult fresh(Long paymentId, long amountMinor, String currency) {
            return new ClaimResult(null, paymentId, amountMinor, currency);
        }
    }

    /** Outcome of {@link #finalizePayment}: the persisted result the orchestrator turns into a response/throw. */
    public record FinalizeResult(com.seatvault.domain.PaymentStatus status, BookingResponse response,
                                 String failureReason) {
    }

    // ======================================================================
    //  CLAIM — reserve the idempotency slot (or replay an existing result)
    // ======================================================================

    /**
     * Validates ownership and, idempotently, either decides the request is a replay
     * of an existing attempt or creates a fresh INITIATED payment row to charge.
     *
     * <p>Replays throw for terminal-but-unsuccessful states so the caller surfaces the
     * original outcome without charging again:
     * <ul>
     *   <li>APPROVED → returns the confirmed booking (success replay).</li>
     *   <li>DECLINED → throws {@link PaymentFailedException} with the original reason.</li>
     *   <li>FAILED → throws {@link ConflictException} (retry needs a new key).</li>
     *   <li>INITIATED → throws {@link ConflictException} (a charge is already in flight).</li>
     * </ul>
     *
     * <p>On the fresh path a unique-violation (lost INSERT race) propagates as
     * {@code DataIntegrityViolationException}; the orchestrator catches it and replays.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ClaimResult claim(Long bookingId, Long userId, String idempotencyKey) {
        // Lock the booking row for the whole claim. This serializes EVERY payment
        // claim for this booking, so two concurrent /pay calls with DIFFERENT
        // idempotency keys cannot both create an in-flight attempt and reach the
        // gateway — the second blocks here until the first claim commits, then sees
        // the INITIATED attempt and is rejected below. The lock is released when this
        // short transaction commits, BEFORE the (out-of-transaction) gateway charge.
        Booking booking = loadOwnedForUpdate(bookingId, userId);

        // Same-key replay (idempotency): return the prior attempt's recorded outcome.
        Payment existing = paymentRepository.findByBookingIdAndIdempotencyKey(bookingId, idempotencyKey).orElse(null);
        if (existing != null) {
            return replayExisting(existing, booking);
        }

        // Different key with an attempt already in flight → refuse before charging.
        if (paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.INITIATED)) {
            throw new ConflictException("A payment for this booking is already in progress; please wait and retry");
        }

        // Fresh key, nothing in flight → the booking must be payable (pending, unexpired).
        if (!booking.isPending()) {
            throw new ConflictException("Booking is not pending (status=" + booking.getStatus() + ")");
        }
        if (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(Instant.now())) {
            throw new ConflictException("Hold has expired; seats were released");
        }

        Payment payment = new Payment(bookingId, userId, idempotencyKey,
                booking.getAmountMinor(), booking.getCurrency());
        // saveAndFlush so a concurrent same-key INSERT (or a second INITIATED row, which
        // the V4 partial unique index forbids) fails NOW with a unique violation rather
        // than silently at commit, letting the orchestrator replay/reject cleanly.
        paymentRepository.saveAndFlush(payment);
        return ClaimResult.fresh(payment.getId(), booking.getAmountMinor(), booking.getCurrency());
    }

    /**
     * Re-reads and replays an existing attempt for this key (used by the orchestrator
     * after losing the INSERT race). Read-only.
     */
    @Transactional(readOnly = true)
    public ClaimResult replay(Long bookingId, Long userId, String idempotencyKey) {
        Booking booking = loadOwned(bookingId, userId);
        Payment existing = paymentRepository.findByBookingIdAndIdempotencyKey(bookingId, idempotencyKey)
                .orElseThrow(() -> new ConflictException("Payment is being processed; please retry"));
        return replayExisting(existing, booking);
    }

    private ClaimResult replayExisting(Payment existing, Booking booking) {
        switch (existing.getStatus()) {
            case APPROVED -> {
                List<Seat> seats = seatRepository.findByBookingId(booking.getId());
                return ClaimResult.replay(BookingResponse.of(booking, seats, existing));
            }
            case DECLINED -> throw new PaymentFailedException(
                    existing.getFailureReason() != null ? existing.getFailureReason() : "Payment was declined");
            case FAILED -> throw new ConflictException(
                    "A previous payment attempt failed; retry with a new idempotency key");
            case INITIATED -> throw new ConflictException(
                    "A payment for this booking is already in progress");
            case REFUNDED -> throw new ConflictException(
                    existing.getFailureReason() != null ? existing.getFailureReason() : "Payment was refunded");
            default -> throw new IllegalStateException("Unknown payment status: " + existing.getStatus());
        }
    }

    // ======================================================================
    //  FINALIZE — record the gateway result and confirm exactly once
    // ======================================================================

    /**
     * Persists the gateway outcome and, on approval, confirms the booking's seats
     * exactly once (via {@link SeatReservationService#confirm}, which joins this
     * transaction and re-locks the rows).
     *
     * <p>This method NEVER throws for a business outcome — it persists DECLINED /
     * FAILED and returns the status so the (non-transactional) orchestrator can throw
     * <em>after</em> this transaction commits. Throwing here would roll back the very
     * record we must keep (requirement: "declined payment is persisted").
     *
     * <p>The "hold expired after approval" case is handled safely: the booking is no
     * longer confirmable, so we mark the payment FAILED with a refund-required reason
     * instead of confirming. (A real gateway integration would issue the refund.)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FinalizeResult finalizePayment(Long paymentId, Long bookingId, Long userId,
                                          PaymentGateway.PaymentResult gatewayResult, Instant now) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));

        // Defensive idempotency: if somehow already finalized, return its current state.
        if (!payment.isInitiated()) {
            return resultFor(payment, bookingId);
        }

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        if (gatewayResult.success()) {
            boolean confirmable = booking.isPending()
                    && (booking.getExpiresAt() == null || !booking.getExpiresAt().isBefore(now));
            if (confirmable) {
                reservationService.confirm(bookingId, userId, now); // exactly-once seat transition, joins txn
                payment.approve(gatewayResult.reference());
            } else {
                payment.fail("Hold expired after payment approval; refund required");
            }
        } else {
            payment.decline(gatewayResult.message());
        }
        paymentRepository.save(payment);
        return resultFor(payment, bookingId);
    }

    /** Marks an INITIATED payment FAILED (e.g. the gateway call threw). Read-committed. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FinalizeResult markFailed(Long paymentId, Long bookingId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        if (payment.isInitiated()) {
            payment.fail(reason);
            paymentRepository.save(payment);
        }
        return resultFor(payment, bookingId);
    }

    private FinalizeResult resultFor(Payment payment, Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
        List<Seat> seats = seatRepository.findByBookingId(bookingId);
        BookingResponse response = BookingResponse.of(booking, seats, payment);
        return new FinalizeResult(payment.getStatus(), response, payment.getFailureReason());
    }

    // ======================================================================
    //  Helpers
    // ======================================================================

    private Booking loadOwned(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getUser().getId().equals(userId)) {
            // 404 (not 403) so we don't reveal that someone else's booking exists.
            throw new ResourceNotFoundException("Booking not found");
        }
        return booking;
    }

    /** Like {@link #loadOwned} but takes a row lock to serialize concurrent claims. */
    private Booking loadOwnedForUpdate(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Booking not found");
        }
        return booking;
    }
}
