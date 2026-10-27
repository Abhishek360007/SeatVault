package com.seatvault.service;

import com.seatvault.config.BookingProperties;
import com.seatvault.domain.Event;
import com.seatvault.dto.booking.BookingResponse;
import com.seatvault.dto.booking.HoldRequest;
import com.seatvault.dto.booking.PaymentRequest;
import com.seatvault.domain.PaymentStatus;
import com.seatvault.domain.Role;
import com.seatvault.domain.User;
import com.seatvault.exception.PaymentFailedException;
import com.seatvault.exception.SeatUnavailableException;
import com.seatvault.payment.PaymentGateway;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.PaymentRepository;
import com.seatvault.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the booking orchestration: strategy dispatch, seat dedupe, and the
 * idempotent pay flow (claim → charge OUTSIDE any lock → finalize), including the
 * replay paths that guarantee a repeated/raced request never double-charges.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock SeatReservationService reservationService;
    @Mock PaymentService paymentService;
    @Mock BookingRepository bookingRepository;
    @Mock SeatRepository seatRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock RedisLockService redisLockService;
    @Mock EventRepository eventRepository;
    @Mock QueueService queueService;
    @Mock CheckoutService checkoutService;

    BookingService bookingService;

    private final User user = user(7L);

    @BeforeEach
    void setUp() {
        BookingProperties properties = new BookingProperties(5,
                BookingProperties.LockingStrategy.PESSIMISTIC, 5, 10, false);
        bookingService = new BookingService(reservationService, paymentService, bookingRepository,
                seatRepository, paymentRepository, paymentGateway, redisLockService, properties,
                eventRepository, queueService, checkoutService);
    }

    /** A normal (non-high-demand) event, so hold() never touches the waiting room. */
    private static Event normalEvent(Long id) {
        Event event = new Event("Concert", "d", "Venue", Instant.now(), 10);
        event.setId(id);
        return event;
    }

    // ----------------------------------------------------------------- HOLD

    @Test
    void holdDedupesAndSortsSeatsThenDispatchesToPessimisticPath() {
        when(eventRepository.findById(99L)).thenReturn(Optional.of(normalEvent(99L)));
        BookingResponse expected = mock(BookingResponse.class);
        when(reservationService.reservePessimistic(eq(user), eq(99L), eq(List.of(1L, 2L)), any(Instant.class)))
                .thenReturn(expected);

        BookingResponse result = bookingService.hold(user, new HoldRequest(99L, List.of(2L, 1L, 2L)));

        assertThat(result).isSameAs(expected);
        verify(reservationService).reservePessimistic(eq(user), eq(99L), eq(List.of(1L, 2L)), any(Instant.class));
        verifyNoInteractions(redisLockService);
    }

    @Test
    void holdRejectsTooManySeats() {
        List<Long> elevenSeats = IntStream.rangeClosed(1, 11).mapToObj(Long::valueOf).toList();

        assertThatThrownBy(() -> bookingService.hold(user, new HoldRequest(99L, elevenSeats)))
                .isInstanceOf(SeatUnavailableException.class);

        verifyNoInteractions(reservationService);
    }

    @Test
    void holdOnHighDemandEventChecksAdmissionBeforeReserving() {
        Event highDemand = normalEvent(99L);
        highDemand.setHighDemand(true);
        when(eventRepository.findById(99L)).thenReturn(Optional.of(highDemand));
        BookingResponse expected = mock(BookingResponse.class);
        when(reservationService.reservePessimistic(eq(user), eq(99L), eq(List.of(1L)), any(Instant.class)))
                .thenReturn(expected);

        BookingResponse result = bookingService.hold(user, new HoldRequest(99L, List.of(1L), "tok-123"));

        assertThat(result).isSameAs(expected);
        verify(queueService).requireAdmission(99L, user.getId(), "tok-123");
    }

    @Test
    void holdOnHighDemandEventPropagatesAdmissionRejection() {
        Event highDemand = normalEvent(99L);
        highDemand.setHighDemand(true);
        when(eventRepository.findById(99L)).thenReturn(Optional.of(highDemand));
        doThrow(new com.seatvault.exception.ForbiddenException("not admitted"))
                .when(queueService).requireAdmission(99L, user.getId(), null);

        assertThatThrownBy(() -> bookingService.hold(user, new HoldRequest(99L, List.of(1L))))
                .isInstanceOf(com.seatvault.exception.ForbiddenException.class);

        verifyNoInteractions(reservationService);
    }

    // ----------------------------------------------------------------- PAY

    @Test
    void payClaimsChargesFrozenAmountThenFinalizesOnApproval() {
        var request = new PaymentRequest("CARD", "key-1");
        when(paymentService.claim(50L, 7L, "key-1"))
                .thenReturn(PaymentService.ClaimResult.fresh(900L, 25_000L, "INR"));
        when(paymentGateway.charge(any())).thenReturn(PaymentGateway.PaymentResult.ok("PAY-X"));
        BookingResponse confirmed = mock(BookingResponse.class);
        when(paymentService.finalizePayment(eq(900L), eq(50L), eq(7L), any(), any(Instant.class)))
                .thenReturn(new PaymentService.FinalizeResult(PaymentStatus.APPROVED, confirmed, null));

        BookingResponse result = bookingService.pay(user, 50L, request);

        assertThat(result).isSameAs(confirmed);
        // Charged exactly once, with the booking's FROZEN amount (not a hardcoded constant).
        var cmd = org.mockito.ArgumentCaptor.forClass(PaymentGateway.PaymentCommand.class);
        verify(paymentGateway, times(1)).charge(cmd.capture());
        assertThat(cmd.getValue().amountMinor()).isEqualTo(25_000L);
        assertThat(cmd.getValue().currency()).isEqualTo("INR");
    }

    @Test
    void payReplaysApprovedResultWithoutChargingAgain() {
        // duplicate pay request (same key already APPROVED) must NOT charge again
        var request = new PaymentRequest("CARD", "key-dup");
        BookingResponse prior = mock(BookingResponse.class);
        when(paymentService.claim(50L, 7L, "key-dup"))
                .thenReturn(PaymentService.ClaimResult.replay(prior));

        BookingResponse result = bookingService.pay(user, 50L, request);

        assertThat(result).isSameAs(prior);
        verifyNoInteractions(paymentGateway);
        verify(paymentService, never()).finalizePayment(any(), any(), any(), any(), any());
    }

    @Test
    void payRaceLostReplaysTheWinnersResult() {
        // concurrent same-key request lost the unique-constraint INSERT race → replay
        var request = new PaymentRequest("CARD", "key-race");
        when(paymentService.claim(50L, 7L, "key-race"))
                .thenThrow(new DataIntegrityViolationException("uq_payments_booking_key"));
        BookingResponse winner = mock(BookingResponse.class);
        when(paymentService.replay(50L, 7L, "key-race"))
                .thenReturn(PaymentService.ClaimResult.replay(winner));

        BookingResponse result = bookingService.pay(user, 50L, request);

        assertThat(result).isSameAs(winner);
        verifyNoInteractions(paymentGateway); // never charged on the lost-race path
    }

    @Test
    void payDeclinedSurfacesPaymentFailedAfterFinalizePersists() {
        var request = new PaymentRequest("FAIL_CARD", "key-2");
        when(paymentService.claim(50L, 7L, "key-2"))
                .thenReturn(PaymentService.ClaimResult.fresh(901L, 25_000L, "INR"));
        when(paymentGateway.charge(any())).thenReturn(PaymentGateway.PaymentResult.declined("Card declined"));
        when(paymentService.finalizePayment(eq(901L), eq(50L), eq(7L), any(), any(Instant.class)))
                .thenReturn(new PaymentService.FinalizeResult(PaymentStatus.DECLINED, mock(BookingResponse.class), "Card declined"));

        assertThatThrownBy(() -> bookingService.pay(user, 50L, request))
                .isInstanceOf(PaymentFailedException.class)
                .hasMessageContaining("Card declined");

        // finalize WAS called (so the DECLINED row is persisted) before we threw.
        verify(paymentService).finalizePayment(eq(901L), eq(50L), eq(7L), any(), any(Instant.class));
    }

    private static User user(Long id) {
        User u = new User("u" + id + "@example.com", "h", "User " + id, Role.USER);
        u.setId(id);
        return u;
    }
}
