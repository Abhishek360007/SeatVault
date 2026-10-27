package com.seatvault.integration;

import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.domain.Event;
import com.seatvault.domain.Payment;
import com.seatvault.domain.PaymentStatus;
import com.seatvault.domain.Role;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.domain.User;
import com.seatvault.dto.booking.BookingResponse;
import com.seatvault.dto.booking.CheckoutResponse;
import com.seatvault.dto.booking.VerifyPaymentRequest;
import com.seatvault.exception.BadRequestException;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.payment.SimulatedPaymentGateway;
import com.seatvault.payment.SimulatedPaymentGateway.Outcome;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.PaymentRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.repository.UserRepository;
import com.seatvault.service.BookingService;
import com.seatvault.service.CheckoutService;
import com.seatvault.service.SeatReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider checkout end to end against real Postgres, using the simulated
 * provider (which signs results exactly as Razorpay does), so the real signature
 * checks, locking and refund paths all run.
 */
class CheckoutIT extends AbstractIntegrationTest {

    @Autowired CheckoutService checkoutService;
    @Autowired BookingService bookingService;
    @Autowired SeatReservationService reservationService;
    @Autowired SimulatedPaymentGateway gateway;
    @Autowired EventRepository eventRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private User alice;
    private User mallory;
    private Long bookingId;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        deleteBookingDependents();
        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();

        alice = userRepository.save(new User("alice@test.local", "hash", "Alice", Role.USER));
        mallory = userRepository.save(new User("mallory@test.local", "hash", "Mallory", Role.USER));
        Event e = new Event("Show", "desc", "Hall", Instant.now().plus(2, ChronoUnit.DAYS), 2);
        e.setBasePriceMinor(50_000);
        e.setConvenienceFeeMinor(2_000);
        e.addSeat(new Seat(e, "A1"));
        e.addSeat(new Seat(e, "A2"));
        Event event = eventRepository.save(e);
        seatIds = seatRepository.findByEventIdOrderBySeatLabel(event.getId()).stream().map(Seat::getId).toList();
        bookingId = reservationService.reservePessimistic(alice, event.getId(), seatIds,
                Instant.now().plus(5, ChronoUnit.MINUTES)).id();
    }

    @Test
    void happyPathConfirmsWithReferenceAndFrozenAmount() {
        CheckoutResponse checkout = checkoutService.startCheckout(alice, bookingId);
        assertThat(checkout.amountMinor()).isEqualTo(2 * 50_000 + 2 * 2_000);
        assertThat(checkout.bookingReference()).matches("SV-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}");

        BookingResponse confirmed = checkoutService.verify(alice, bookingId, pay(checkout, Outcome.SUCCESS));

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.reference()).isEqualTo(checkout.bookingReference());
        assertThat(confirmed.payment().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(seatStatuses()).containsOnly(SeatStatus.BOOKED);
    }

    @Test
    void startingCheckoutAgainReusesTheSameOrder() {
        String first = checkoutService.startCheckout(alice, bookingId).orderId();
        String second = checkoutService.startCheckout(alice, bookingId).orderId();

        assertThat(second).isEqualTo(first);
        assertThat(paymentRepository.findAll()).hasSize(1);
    }

    @Test
    void repeatedCallbacksAndWebhooksConfirmExactlyOnce() {
        CheckoutResponse checkout = checkoutService.startCheckout(alice, bookingId);
        VerifyPaymentRequest paid = pay(checkout, Outcome.SUCCESS);

        checkoutService.verify(alice, bookingId, paid);
        checkoutService.verify(alice, bookingId, paid);
        checkoutService.handleWebhook(capturedWebhook(paid), gateway.signWebhook(capturedWebhook(paid)));

        assertThat(booking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paymentRepository.findAll()).singleElement()
                .extracting(Payment::getStatus).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void callbackAndWebhookRacingStillConfirmOnce() throws Exception {
        CheckoutResponse checkout = checkoutService.startCheckout(alice, bookingId);
        VerifyPaymentRequest paid = pay(checkout, Outcome.SUCCESS);
        String body = capturedWebhook(paid);
        String sig = gateway.signWebhook(body);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            boolean webhook = i % 2 == 0;
            calls.add(pool.submit(() -> {
                go.await();
                if (webhook) {
                    checkoutService.handleWebhook(body, sig);
                } else {
                    checkoutService.verify(alice, bookingId, paid);
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : calls) {
            f.get(); // none may throw
        }
        pool.shutdown();

        assertThat(booking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paymentRepository.findAll()).singleElement()
                .extracting(Payment::getStatus).isEqualTo(PaymentStatus.APPROVED);
        assertThat(seatStatuses()).containsOnly(SeatStatus.BOOKED);
    }

    @Test
    void webhookAloneConfirmsWhenTheBrowserNeverComesBack() {
        VerifyPaymentRequest paid = pay(checkoutService.startCheckout(alice, bookingId), Outcome.SUCCESS);

        checkoutService.handleWebhook(capturedWebhook(paid), gateway.signWebhook(capturedWebhook(paid)));

        assertThat(booking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void forgedSignaturesAreRejected() {
        VerifyPaymentRequest paid = pay(checkoutService.startCheckout(alice, bookingId), Outcome.SUCCESS);
        VerifyPaymentRequest forged = new VerifyPaymentRequest(paid.orderId(), paid.paymentId(), "0".repeat(64));

        assertThatThrownBy(() -> checkoutService.verify(alice, bookingId, forged))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> checkoutService.handleWebhook(capturedWebhook(paid), "bad"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> checkoutService.handleWebhook(capturedWebhook(paid), null))
                .isInstanceOf(BadRequestException.class);
        assertThat(booking().getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void declinedAttemptKeepsTheOrderOpenForARetry() {
        CheckoutResponse checkout = checkoutService.startCheckout(alice, bookingId);
        var declined = gateway.simulateCheckout(checkout.orderId(), Outcome.FAILURE, "CARD");
        checkoutService.recordClientFailure(alice, bookingId, checkout.orderId(), declined.errorDescription());

        Payment open = paymentRepository.findByProviderOrderId(checkout.orderId()).orElseThrow();
        assertThat(open.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(open.getFailureReason()).contains("declined");

        BookingResponse confirmed = checkoutService.verify(alice, bookingId, pay(checkout, Outcome.SUCCESS));
        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void paymentAfterCancellationIsRefundedNotConfirmed() {
        CheckoutResponse checkout = checkoutService.startCheckout(alice, bookingId);
        bookingService.cancel(alice, bookingId);

        VerifyPaymentRequest late = pay(checkout, Outcome.SUCCESS);
        assertThatThrownBy(() -> checkoutService.verify(alice, bookingId, late))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("refunded");

        assertThat(booking().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        Payment payment = paymentRepository.findByProviderOrderId(checkout.orderId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getProviderReference()).startsWith("rfnd_sim_");
        assertThat(seatStatuses()).containsOnly(SeatStatus.AVAILABLE);

        // A repeat of the same callback must not refund twice.
        assertThatThrownBy(() -> checkoutService.verify(alice, bookingId, late)).isInstanceOf(ConflictException.class);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getProviderReference())
                .isEqualTo(payment.getProviderReference());
    }

    @Test
    void paymentAfterTheHoldExpiredIsRefunded() {
        CheckoutResponse checkout = checkoutService.startCheckout(alice, bookingId);
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 second' WHERE id = ?", bookingId);
        jdbc.update("UPDATE seats SET hold_expires_at = now() - interval '1 second' WHERE booking_id = ?", bookingId);

        assertThatThrownBy(() -> checkoutService.verify(alice, bookingId, pay(checkout, Outcome.SUCCESS)))
                .isInstanceOf(ConflictException.class);

        assertThat(booking().getStatus()).isEqualTo(BookingStatus.PENDING); // the sweep will expire it
        assertThat(paymentRepository.findByProviderOrderId(checkout.orderId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void reconciliationFinishesPaymentsWhoseSignalsWereLost() {
        CheckoutResponse checkout = checkoutService.startCheckout(alice, bookingId);
        pay(checkout, Outcome.SUCCESS); // paid at the provider, but no callback and no webhook

        assertThat(checkoutService.reconcile(Instant.now())).isZero(); // too recent to chase yet
        assertThat(checkoutService.reconcile(Instant.now().plus(10, ChronoUnit.MINUTES))).isEqualTo(1);

        assertThat(booking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void otherUsersCannotCheckOutOrVerifySomeoneElsesBooking() {
        assertThatThrownBy(() -> checkoutService.startCheckout(mallory, bookingId))
                .isInstanceOf(ResourceNotFoundException.class);

        VerifyPaymentRequest paid = pay(checkoutService.startCheckout(alice, bookingId), Outcome.SUCCESS);
        assertThatThrownBy(() -> checkoutService.verify(mallory, bookingId, paid))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cannotCheckOutAConfirmedBooking() {
        checkoutService.verify(alice, bookingId, pay(checkoutService.startCheckout(alice, bookingId), Outcome.SUCCESS));

        assertThatThrownBy(() -> checkoutService.startCheckout(alice, bookingId))
                .isInstanceOf(ConflictException.class);
    }

    // ------------------------------------------------------------------ helpers

    /** The customer finishing the (simulated) widget; returns what the browser would post to verify. */
    private VerifyPaymentRequest pay(CheckoutResponse checkout, Outcome outcome) {
        var result = gateway.simulateCheckout(checkout.orderId(), outcome, "CARD");
        return new VerifyPaymentRequest(result.orderId(), result.paymentId(), result.signature());
    }

    private static String capturedWebhook(VerifyPaymentRequest paid) {
        return """
                {"event":"payment.captured","payload":{"payment":{"entity":\
                {"id":"%s","order_id":"%s","status":"captured"}}}}""".formatted(paid.paymentId(), paid.orderId());
    }

    private Booking booking() {
        return bookingRepository.findById(bookingId).orElseThrow();
    }

    private List<SeatStatus> seatStatuses() {
        return seatIds.stream().map(id -> seatRepository.findById(id).orElseThrow().getStatus()).toList();
    }
}
