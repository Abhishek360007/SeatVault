package com.seatvault.service;

import com.seatvault.domain.Booking;
import com.seatvault.domain.Event;
import com.seatvault.domain.Payment;
import com.seatvault.domain.PaymentStatus;
import com.seatvault.domain.Role;
import com.seatvault.domain.User;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.PaymentFailedException;
import com.seatvault.payment.PaymentGateway;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.PaymentRepository;
import com.seatvault.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the payment lifecycle persistence + idempotency in
 * {@link PaymentService}: booking-level serialization (one in-flight attempt),
 * declined persistence, the expired-hold-after-approval safety path, exactly-once
 * confirm, and replay of an already-approved/declined attempt.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock BookingRepository bookingRepository;
    @Mock SeatRepository seatRepository;
    @Mock SeatReservationService reservationService;

    @InjectMocks PaymentService paymentService;

    private static final Instant NOW = Instant.parse("2027-01-01T10:00:00Z");

    // ----------------------------------------------------- finalize: declined

    @Test
    void declinedPaymentIsPersistedAndDoesNotConfirm() {
        Payment payment = initiatedPayment(10L, 1L, 7L);
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(payment));
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(pendingBooking(1L, 7L)));
        when(seatRepository.findByBookingId(1L)).thenReturn(List.of());

        var result = paymentService.finalizePayment(10L, 1L, 7L,
                PaymentGateway.PaymentResult.declined("Card declined"), NOW);

        assertThat(result.status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getFailureReason()).isEqualTo("Card declined");
        verify(paymentRepository).save(payment);             // DECLINED is persisted
        verify(reservationService, never()).confirm(anyLong(), anyLong(), any()); // no confirm
    }

    // ----------------------------------------------- finalize: approved (once)

    @Test
    void approvedPaymentConfirmsExactlyOnceAndPersistsApproval() {
        Payment payment = initiatedPayment(11L, 2L, 7L);
        when(paymentRepository.findById(11L)).thenReturn(Optional.of(payment));
        when(bookingRepository.findById(2L)).thenReturn(Optional.of(pendingBooking(2L, 7L)));
        when(seatRepository.findByBookingId(2L)).thenReturn(List.of());

        var result = paymentService.finalizePayment(11L, 2L, 7L,
                PaymentGateway.PaymentResult.ok("PAY-OK"), NOW);

        assertThat(result.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getProviderReference()).isEqualTo("PAY-OK");
        verify(reservationService, times(1)).confirm(eq(2L), eq(7L), eq(NOW)); // exactly once
    }

    @Test
    void alreadyFinalizedPaymentIsNotConfirmedAgain() {
        Payment approved = initiatedPayment(12L, 3L, 7L);
        approved.approve("PAY-PRIOR");
        when(paymentRepository.findById(12L)).thenReturn(Optional.of(approved));
        when(bookingRepository.findById(3L)).thenReturn(Optional.of(pendingBooking(3L, 7L)));
        when(seatRepository.findByBookingId(3L)).thenReturn(List.of());

        var result = paymentService.finalizePayment(12L, 3L, 7L,
                PaymentGateway.PaymentResult.ok("PAY-NEW"), NOW);

        assertThat(result.status()).isEqualTo(PaymentStatus.APPROVED);
        verify(reservationService, never()).confirm(anyLong(), anyLong(), any());
        verify(paymentRepository, never()).save(any());
    }

    // ------------------------------------ finalize: expired hold after approval

    @Test
    void expiredHoldAfterApprovalIsHandledSafely() {
        Payment payment = initiatedPayment(13L, 4L, 7L);
        Booking expired = pendingBooking(4L, 7L);
        expired.setExpiresAt(NOW.minus(1, ChronoUnit.MINUTES)); // hold lapsed before payment landed
        when(paymentRepository.findById(13L)).thenReturn(Optional.of(payment));
        when(bookingRepository.findById(4L)).thenReturn(Optional.of(expired));
        when(seatRepository.findByBookingId(4L)).thenReturn(List.of());

        var result = paymentService.finalizePayment(13L, 4L, 7L,
                PaymentGateway.PaymentResult.ok("PAY-LATE"), NOW);

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).contains("refund");
        verify(reservationService, never()).confirm(anyLong(), anyLong(), any());
        verify(paymentRepository).save(payment);
    }

    // --------------------------------------------------- claim: replay + create

    @Test
    void claimReplaysApprovedAttemptWithoutCreatingNewRow() {
        // same booking + same idempotency key already APPROVED → replay, no new charge
        Booking booking = confirmedBooking(5L, 7L);
        Payment approved = initiatedPayment(14L, 5L, 7L);
        approved.approve("PAY-DONE");
        when(bookingRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdAndIdempotencyKey(5L, "key")).thenReturn(Optional.of(approved));
        when(seatRepository.findByBookingId(5L)).thenReturn(List.of());

        PaymentService.ClaimResult result = paymentService.claim(5L, 7L, "key");

        assertThat(result.isApprovedReplay()).isTrue();
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void confirmedBookingWithDifferentKeyReturns409WithoutCharging() {
        // already confirmed, but a DIFFERENT key arrives → clean 409, never charge
        Booking booking = confirmedBooking(5L, 7L);
        when(bookingRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdAndIdempotencyKey(5L, "other-key")).thenReturn(Optional.empty());
        when(paymentRepository.existsByBookingIdAndStatus(5L, PaymentStatus.INITIATED)).thenReturn(false);

        assertThatThrownBy(() -> paymentService.claim(5L, 7L, "other-key"))
                .isInstanceOf(ConflictException.class);
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void claimReplaysDeclinedAttemptAsPaymentFailed() {
        Booking booking = pendingBooking(6L, 7L);
        Payment declined = initiatedPayment(15L, 6L, 7L);
        declined.decline("Card declined");
        when(bookingRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdAndIdempotencyKey(6L, "k")).thenReturn(Optional.of(declined));

        assertThatThrownBy(() -> paymentService.claim(6L, 7L, "k"))
                .isInstanceOf(PaymentFailedException.class)
                .hasMessageContaining("Card declined");
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void claimRejectsConcurrentDifferentKeyWhileAnotherIsInitiated() {
        // booking-level serialization: a fresh key is refused while an INITIATED attempt exists
        Booking booking = pendingBooking(7L, 7L);
        when(bookingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdAndIdempotencyKey(7L, "key-B")).thenReturn(Optional.empty());
        when(paymentRepository.existsByBookingIdAndStatus(7L, PaymentStatus.INITIATED)).thenReturn(true);

        assertThatThrownBy(() -> paymentService.claim(7L, 7L, "key-B"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already in progress");
        verify(paymentRepository, never()).saveAndFlush(any()); // never reaches the gateway
    }

    @Test
    void claimCreatesInitiatedRowWithFrozenAmountForFreshKey() {
        Booking booking = pendingBooking(8L, 7L);
        booking.setCurrency("INR");
        booking.setAmountMinor(30_000L);
        when(bookingRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdAndIdempotencyKey(8L, "fresh")).thenReturn(Optional.empty());
        when(paymentRepository.existsByBookingIdAndStatus(8L, PaymentStatus.INITIATED)).thenReturn(false);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(99L);
            return p;
        });

        PaymentService.ClaimResult result = paymentService.claim(8L, 7L, "fresh");

        assertThat(result.isApprovedReplay()).isFalse();
        assertThat(result.paymentId()).isEqualTo(99L);
        assertThat(result.amountMinor()).isEqualTo(30_000L);
        assertThat(result.currency()).isEqualTo("INR");

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(saved.getValue().getAmountMinor()).isEqualTo(30_000L);
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("fresh");
    }

    @Test
    void claimAllowsNewKeyRetryAfterDeclinedWhenNothingInFlight() {
        // a prior DECLINED attempt exists (different key), nothing INITIATED → new key allowed
        Booking booking = pendingBooking(20L, 7L);
        booking.setAmountMinor(15_000L);
        when(bookingRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdAndIdempotencyKey(20L, "new-key")).thenReturn(Optional.empty());
        when(paymentRepository.existsByBookingIdAndStatus(20L, PaymentStatus.INITIATED)).thenReturn(false);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(200L);
            return p;
        });

        PaymentService.ClaimResult result = paymentService.claim(20L, 7L, "new-key");

        assertThat(result.isApprovedReplay()).isFalse();
        assertThat(result.paymentId()).isEqualTo(200L);
        verify(paymentRepository).saveAndFlush(any(Payment.class)); // retry proceeds
    }

    @Test
    void claimRejectsNonPendingBookingForFreshKey() {
        Booking confirmed = confirmedBooking(9L, 7L);
        when(bookingRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(confirmed));
        when(paymentRepository.findByBookingIdAndIdempotencyKey(9L, "x")).thenReturn(Optional.empty());
        when(paymentRepository.existsByBookingIdAndStatus(9L, PaymentStatus.INITIATED)).thenReturn(false);

        assertThatThrownBy(() -> paymentService.claim(9L, 7L, "x"))
                .isInstanceOf(ConflictException.class);
    }

    // ----------------------------------------------------------------- helpers

    private Payment initiatedPayment(Long id, Long bookingId, Long userId) {
        Payment p = new Payment(bookingId, userId, "key-" + id, 25_000L, "INR");
        p.setId(id);
        return p;
    }

    private Booking pendingBooking(Long id, Long userId) {
        Event event = new Event("E", "d", "V", NOW.plus(2, ChronoUnit.DAYS), 2);
        event.setId(100L);
        event.setCurrency("INR");
        Booking b = new Booking(user(userId), event, 2, NOW.plus(5, ChronoUnit.MINUTES), "INR", 25_000L, 0L);
        b.setId(id);
        return b;
    }

    private Booking confirmedBooking(Long id, Long userId) {
        Booking b = pendingBooking(id, userId);
        b.confirm(NOW);
        return b;
    }

    private static User user(Long id) {
        User u = new User("u" + id + "@example.com", "h", "User " + id, Role.USER);
        u.setId(id);
        return u;
    }
}
