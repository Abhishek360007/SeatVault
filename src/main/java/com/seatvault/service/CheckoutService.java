package com.seatvault.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatvault.config.PaymentProperties;
import com.seatvault.domain.Booking;
import com.seatvault.domain.Payment;
import com.seatvault.domain.PaymentStatus;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.domain.User;
import com.seatvault.dto.booking.BookingResponse;
import com.seatvault.dto.booking.CheckoutResponse;
import com.seatvault.dto.booking.VerifyPaymentRequest;
import com.seatvault.exception.BadRequestException;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.PaymentFailedException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.notification.NotificationService;
import com.seatvault.notification.NotificationType;
import com.seatvault.payment.PaymentGateway;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.PaymentRepository;
import com.seatvault.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Provider checkout: open an order for a held booking, then confirm it exactly
 * once from whichever signed signal arrives first — the browser callback
 * ({@link #verify}), the provider webhook ({@link #handleWebhook}) or the
 * reconciliation sweep ({@link #reconcile}).
 *
 * <h2>Exactly-once confirmation</h2>
 * All three paths end in {@link #complete}. It asks the provider for the payment's
 * real state (outside any transaction — never block on the network holding locks),
 * then, under the booking's row lock, re-reads the payment: if another path already
 * finished it, it returns that result instead. One payment row per provider order
 * (unique {@code provider_order_id}) and at most one open order per booking (the V4
 * partial index) mean there is nothing else to double-apply.
 *
 * <h2>Money that arrives too late</h2>
 * If the hold expired or the booking was cancelled before the money was captured,
 * the booking is NOT confirmed (its seats may already belong to someone else); the
 * payment is refunded in full and marked REFUNDED. Everything the confirm needs is
 * checked first, because {@link SeatReservationService#confirm} throwing inside our
 * transaction would mark it rollback-only and lose the payment record.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** How the {@link #complete} attempt ended. */
    public enum Outcome { CONFIRMED, NOT_CAPTURED, REFUNDED, REFUND_FAILED }

    public record Completion(Outcome outcome, String message, Long bookingId) {
    }

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final SeatRepository seatRepository;
    private final SeatReservationService reservationService;
    private final PaymentGateway gateway;
    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final NotificationService notificationService;

    public CheckoutService(BookingRepository bookingRepository,
                           PaymentRepository paymentRepository,
                           SeatRepository seatRepository,
                           SeatReservationService reservationService,
                           PaymentGateway gateway,
                           PaymentProperties properties,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager,
                           NotificationService notificationService) {
        this.notificationService = notificationService;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.seatRepository = seatRepository;
        this.reservationService = reservationService;
        this.gateway = gateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // ======================================================================
    //  START — one provider order per booking, reused until it's paid
    // ======================================================================

    /**
     * Returns the booking's open order, creating it on first call. Calling again (a
     * page refresh, a retry after a declined card) reuses the same order, which the
     * provider lets the customer attempt again until one attempt succeeds.
     */
    public CheckoutResponse startCheckout(User user, Long bookingId) {
        CheckoutResponse existing = tx.execute(s -> {
            Booking booking = lockOwnedPayable(bookingId, user.getId());
            Payment open = openPayment(bookingId);
            return open == null ? null : toCheckout(booking, open, user);
        });
        if (existing != null) {
            return existing;
        }

        // Network call outside any transaction. Amount and currency come from the
        // booking row (frozen at hold time), never from the request.
        Booking snapshot = bookingRepository.findById(bookingId).orElseThrow(() -> notFound());
        PaymentGateway.ProviderOrder order;
        try {
            order = gateway.createOrder(new PaymentGateway.OrderCommand(
                    bookingId, snapshot.getReference(), snapshot.getAmountMinor(), snapshot.getCurrency()));
        } catch (RuntimeException e) {
            log.warn("Could not create {} order for booking={}: {}", gateway.providerName(), bookingId, e.toString());
            throw new PaymentFailedException("Payment provider is unavailable; please try again");
        }

        try {
            return tx.execute(s -> {
                Booking booking = lockOwnedPayable(bookingId, user.getId());
                Payment open = openPayment(bookingId);
                if (open != null) {
                    // A concurrent call won; its order is the one to pay. Ours is never
                    // shown to anyone, so it can't be paid and simply lapses at the provider.
                    return toCheckout(booking, open, user);
                }
                Payment payment = new Payment(bookingId, user.getId(), "order:" + order.orderId(),
                        booking.getAmountMinor(), booking.getCurrency());
                payment.setProvider(gateway.providerName());
                payment.setProviderOrderId(order.orderId());
                paymentRepository.saveAndFlush(payment);
                log.info("Opened {} order {} for booking={} ({})", gateway.providerName(), order.orderId(),
                        bookingId, booking.getReference());
                return toCheckout(booking, payment, user);
            });
        } catch (DataIntegrityViolationException raceLost) {
            // The partial unique index refused a second open payment: return the winner's.
            return tx.execute(s -> toCheckout(lockOwnedPayable(bookingId, user.getId()), openPayment(bookingId), user));
        }
    }

    // ======================================================================
    //  VERIFY — the browser's success callback
    // ======================================================================

    public BookingResponse verify(User user, Long bookingId, VerifyPaymentRequest request) {
        Payment payment = paymentRepository.findByProviderOrderId(request.orderId())
                .filter(p -> p.getBookingId().equals(bookingId) && p.getUserId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!gateway.verifyCheckoutSignature(request.orderId(), request.paymentId(), request.signature())) {
            log.warn("Rejected checkout callback with a bad signature: booking={} order={}", bookingId, request.orderId());
            throw new BadRequestException("Payment signature is invalid");
        }

        Completion done = complete(payment.getId(), request.paymentId());
        return switch (done.outcome()) {
            case CONFIRMED -> bookingResponse(bookingId);
            case NOT_CAPTURED -> throw new PaymentFailedException(done.message());
            case REFUNDED, REFUND_FAILED -> throw new ConflictException(done.message());
        };
    }

    /** The browser reporting a failed attempt; kept on the open payment for display. */
    public void recordClientFailure(User user, Long bookingId, String orderId, String reason) {
        tx.executeWithoutResult(s -> paymentRepository.findByProviderOrderId(orderId)
                .filter(p -> p.getBookingId().equals(bookingId) && p.getUserId().equals(user.getId()))
                .filter(Payment::isInitiated)
                .ifPresent(p -> p.recordAttemptFailure(reason == null || reason.isBlank() ? "Payment failed" : reason)));
    }

    // ======================================================================
    //  WEBHOOK — the provider telling us directly
    // ======================================================================

    /**
     * Handles a provider webhook. Unknown event types and orders that aren't ours
     * are acknowledged and ignored, so the provider stops retrying them. A webhook
     * repeated or arriving after the browser callback finds the payment already
     * finished and changes nothing.
     */
    public void handleWebhook(String rawBody, String signature) {
        if (!gateway.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Rejected payment webhook with a missing or bad signature");
            throw new BadRequestException("Invalid webhook signature");
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            throw new BadRequestException("Malformed webhook body");
        }
        String type = event.path("event").asText();
        JsonNode entity = event.path("payload").path("payment").path("entity");
        String orderId = entity.path("order_id").asText(null);
        String paymentId = entity.path("id").asText(null);
        if (orderId == null || paymentId == null) {
            log.debug("Ignoring webhook {} without a payment entity", type);
            return;
        }
        Payment payment = paymentRepository.findByProviderOrderId(orderId).orElse(null);
        if (payment == null) {
            log.info("Ignoring webhook {} for unknown order {}", type, orderId);
            return;
        }
        switch (type) {
            case "payment.captured", "order.paid", "payment.authorized" -> {
                Completion done = complete(payment.getId(), paymentId);
                log.info("Webhook {} for order {} → {}", type, orderId, done.outcome());
            }
            case "payment.failed" -> tx.executeWithoutResult(s -> paymentRepository.findById(payment.getId())
                    .filter(Payment::isInitiated)
                    .ifPresent(p -> p.recordAttemptFailure(entity.path("error_description").asText("Payment failed"))));
            default -> log.debug("Ignoring webhook {}", type);
        }
    }

    // ======================================================================
    //  RECONCILE — catch payments whose callback and webhook both went missing
    // ======================================================================

    /**
     * Asks the provider about orders open for longer than
     * {@code app.payment.reconcile-after-seconds}. Paid ones are completed (confirmed,
     * or refunded if the booking has lapsed); unpaid ones whose booking is no longer
     * pending are closed. Returns how many orders it resolved.
     */
    public int reconcile(Instant now) {
        Instant cutoff = now.minusSeconds(properties.reconcileAfterSeconds());
        int resolved = 0;
        for (Payment open : paymentRepository.findOpenOrdersCreatedBefore(cutoff, gateway.providerName())) {
            try {
                var captured = gateway.findCapturedPayment(open.getProviderOrderId());
                if (captured.isPresent()) {
                    complete(open.getId(), captured.get());
                    resolved++;
                } else if (closeIfBookingEnded(open.getId())) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                log.warn("Reconciliation of order {} failed; will retry: {}", open.getProviderOrderId(), e.toString());
            }
        }
        return resolved;
    }

    /** Closes the booking's open order (on cancel), so it can't be confirmed later. */
    public void closeOpenOrder(Long bookingId, String reason) {
        tx.executeWithoutResult(s -> {
            Payment open = openPayment(bookingId);
            if (open != null && open.getProviderOrderId() != null) {
                open.fail(reason);
            }
        });
    }

    // ======================================================================
    //  COMPLETE — the single, idempotent finish line
    // ======================================================================

    /**
     * Finishes a payment row given the provider's payment id. Safe to call any number
     * of times, from any path, concurrently.
     */
    public Completion complete(Long paymentRowId, String providerPaymentId) {
        Payment snapshot = paymentRepository.findById(paymentRowId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        Completion already = finished(snapshot);
        if (already != null) {
            return already;
        }

        // 1) The provider's own answer, outside any transaction.
        PaymentGateway.CaptureResult capture;
        try {
            capture = gateway.capture(snapshot.getProviderOrderId(), providerPaymentId,
                    snapshot.getAmountMinor(), snapshot.getCurrency());
        } catch (RuntimeException e) {
            log.warn("Capture check failed for order {}: {}", snapshot.getProviderOrderId(), e.toString());
            return new Completion(Outcome.NOT_CAPTURED, "Payment provider error; please try again", snapshot.getBookingId());
        }
        if (!capture.captured()) {
            tx.executeWithoutResult(s -> paymentRepository.findById(paymentRowId)
                    .filter(Payment::isInitiated)
                    .ifPresent(p -> p.recordAttemptFailure(capture.message())));
            return new Completion(Outcome.NOT_CAPTURED, capture.message(), snapshot.getBookingId());
        }

        // 2) Confirm under the booking lock, or decide the money must go back.
        Completion decided = tx.execute(s -> {
            Booking booking = bookingRepository.findByIdForUpdate(snapshot.getBookingId()).orElseThrow(() -> notFound());
            Payment payment = paymentRepository.findById(paymentRowId).orElseThrow();
            Completion raced = finished(payment);
            if (raced != null) {
                return raced; // another path finished it while we were talking to the provider
            }
            Instant now = Instant.now();
            String blocker = confirmBlocker(booking, now);
            if (blocker == null) {
                reservationService.confirm(booking.getId(), payment.getUserId(), now);
                payment.approve(providerPaymentId);
                log.info("Confirmed booking {} ({}) from {} payment {}",
                        booking.getId(), booking.getReference(), gateway.providerName(), providerPaymentId);
                return new Completion(Outcome.CONFIRMED, "Payment successful", booking.getId());
            }
            // Mark it as refunding with the payment id, so a concurrent completion sees
            // it as finished and doesn't refund a second time.
            payment.fail("Paid after " + blocker + "; refund pending");
            payment.setProviderReference(providerPaymentId);
            return new Completion(null, blocker, booking.getId()); // null outcome = refund below
        });
        if (decided.outcome() != null) {
            return decided;
        }

        // 3) Refund outside the transaction.
        Booking booking = bookingRepository.findById(snapshot.getBookingId()).orElseThrow(() -> notFound());
        String why = "Paid after " + decided.message() + "; refunded in full";
        try {
            String refundId = gateway.refund(providerPaymentId, snapshot.getAmountMinor(), why);
            tx.executeWithoutResult(s -> {
                paymentRepository.findById(paymentRowId).orElseThrow().refund(refundId, why);
                notificationService.enqueue(NotificationType.PAYMENT_REFUNDED,
                        bookingRepository.findById(snapshot.getBookingId()).orElseThrow());
            });
            log.warn("Refunded payment {} for booking {}: {}", providerPaymentId, booking.getId(), why);
            return new Completion(Outcome.REFUNDED,
                    "Your seat hold ended before the payment completed, so you have been refunded in full.",
                    booking.getId());
        } catch (RuntimeException e) {
            log.error("REFUND REQUIRED: payment {} for booking {} could not be refunded automatically: {}",
                    providerPaymentId, booking.getId(), e.toString());
            tx.executeWithoutResult(s -> paymentRepository.findById(paymentRowId).orElseThrow()
                    .fail("Paid after booking ended; automatic refund failed, refund manually"));
            return new Completion(Outcome.REFUND_FAILED,
                    "Your seat hold ended before the payment completed. A refund will be issued; please contact support.",
                    booking.getId());
        }
    }

    // ======================================================================
    //  Helpers
    // ======================================================================

    /** The outcome of a payment some path already finished, or null if it's still open. */
    private Completion finished(Payment p) {
        return switch (p.getStatus()) {
            case APPROVED -> new Completion(Outcome.CONFIRMED, "Payment successful", p.getBookingId());
            case REFUNDED -> new Completion(Outcome.REFUNDED,
                    "Your seat hold ended before the payment completed, so you have been refunded in full.",
                    p.getBookingId());
            // FAILED with a provider payment id = money taken, refund pending or failed.
            case FAILED -> p.getProviderReference() != null
                    ? new Completion(Outcome.REFUND_FAILED, p.getFailureReason(), p.getBookingId())
                    : null; // closed unpaid order: still check, in case it was paid at the last moment
            case INITIATED, DECLINED -> null;
        };
    }

    /**
     * Why this booking can't be confirmed now, or null if it can. Mirrors every check
     * {@link SeatReservationService#confirm} makes, so that call never throws here.
     */
    private String confirmBlocker(Booking booking, Instant now) {
        if (!booking.isPending()) {
            return "the booking was " + booking.getStatus().name().toLowerCase();
        }
        if (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(now)) {
            return "the seat hold expired";
        }
        List<Seat> seats = seatRepository.findByBookingId(booking.getId());
        boolean allHeld = !seats.isEmpty() && seats.stream()
                .allMatch(seat -> seat.getStatus() == SeatStatus.HELD && booking.getId().equals(seat.getBookingId()));
        return allHeld ? null : "the seats were released";
    }

    private boolean closeIfBookingEnded(Long paymentRowId) {
        Boolean closed = tx.execute(s -> {
            Payment p = paymentRepository.findById(paymentRowId).orElseThrow();
            Booking b = bookingRepository.findById(p.getBookingId()).orElseThrow();
            if (p.isInitiated() && !b.isPending()) {
                p.fail("Order closed unpaid: booking " + b.getStatus().name().toLowerCase());
                return true;
            }
            return false;
        });
        return Boolean.TRUE.equals(closed);
    }

    private Booking lockOwnedPayable(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElseThrow(() -> notFound());
        if (!booking.getUser().getId().equals(userId)) {
            throw notFound(); // 404, not 403: don't reveal other people's bookings
        }
        if (!booking.isPending()) {
            throw new ConflictException("Booking is not awaiting payment (status=" + booking.getStatus() + ")");
        }
        if (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(Instant.now())) {
            throw new ConflictException("Your seat hold has expired; please pick your seats again");
        }
        Payment inFlight = paymentRepository.findTopByBookingIdAndStatusOrderByIdDesc(bookingId, PaymentStatus.INITIATED)
                .orElse(null);
        if (inFlight != null && inFlight.getProviderOrderId() == null) {
            throw new ConflictException("A direct payment for this booking is already in progress");
        }
        return booking;
    }

    /** The booking's open order for the current provider, if any. */
    private Payment openPayment(Long bookingId) {
        return paymentRepository.findTopByBookingIdAndStatusOrderByIdDesc(bookingId, PaymentStatus.INITIATED)
                .filter(p -> p.getProviderOrderId() != null && gateway.providerName().equals(p.getProvider()))
                .orElse(null);
    }

    private CheckoutResponse toCheckout(Booking booking, Payment payment, User user) {
        return new CheckoutResponse(
                gateway.providerName(),
                gateway.publicKeyId(),
                payment.getProviderOrderId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                booking.getId(),
                booking.getReference(),
                properties.merchantName(),
                booking.getEvent().getTitle() + " · " + booking.getSeatCount() + " seat(s)",
                user.getFullName(),
                user.getEmail(),
                booking.getExpiresAt());
    }

    private BookingResponse bookingResponse(Long bookingId) {
        return tx.execute(s -> {
            Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> notFound());
            Payment payment = paymentRepository.findTopByBookingIdOrderByIdDesc(bookingId).orElse(null);
            return BookingResponse.of(booking, seatRepository.findByBookingId(bookingId), payment);
        });
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Booking not found");
    }
}
