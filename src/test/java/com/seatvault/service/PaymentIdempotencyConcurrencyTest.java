package com.seatvault.service;

import com.seatvault.config.BookingProperties;
import com.seatvault.domain.Booking;
import com.seatvault.domain.Event;
import com.seatvault.domain.Payment;
import com.seatvault.dto.booking.BookingResponse;
import com.seatvault.dto.booking.PaymentRequest;
import com.seatvault.domain.Role;
import com.seatvault.domain.User;
import com.seatvault.payment.PaymentGateway;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.PaymentRepository;
import com.seatvault.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the idempotency contract under real concurrency: many simultaneous
 * {@code /pay} calls carrying the SAME idempotency key must charge the gateway at
 * most once and confirm the booking at most once.
 *
 * <p>It wires the REAL {@link BookingService} + {@link PaymentService} together and
 * backs the {@link PaymentRepository} with an in-memory fake that faithfully
 * emulates the database's unique {@code (booking_id, idempotency_key)} constraint —
 * a duplicate {@code saveAndFlush} throws {@link DataIntegrityViolationException},
 * exactly as Postgres would. That constraint is the serialization point that makes
 * the charge exactly-once; this test exercises it from 24 threads.
 */
class PaymentIdempotencyConcurrencyTest {

    private static final int THREADS = 24;
    private static final long BOOKING_ID = 1L;
    private static final long USER_ID = 7L;
    private static final String KEY = "same-key-for-all";

    @Test
    void concurrentSameKeyPayChargesOnceAndConfirmsOnce() throws InterruptedException {
        // ---- in-memory payment store emulating the unique (booking_id, key) constraint
        Map<String, Payment> byKey = new ConcurrentHashMap<>();
        Map<Long, Payment> byId = new ConcurrentHashMap<>();
        AtomicLong idSeq = new AtomicLong();

        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        when(paymentRepository.findByBookingIdAndIdempotencyKey(anyLong(), any()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(byKey.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) p.setId(idSeq.incrementAndGet());
            String k = p.getBookingId() + "|" + p.getIdempotencyKey();
            if (byKey.putIfAbsent(k, p) != null) {
                throw new DataIntegrityViolationException("uq_payments_booking_key"); // the DB guard
            }
            byId.put(p.getId(), p);
            return p;
        });
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            byId.put(p.getId(), p);
            return p;
        });
        when(paymentRepository.findById(anyLong()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(byId.get(inv.getArgument(0))));

        // emulate the booking-level serialization guard from the store
        when(paymentRepository.existsByBookingIdAndStatus(anyLong(), any())).thenAnswer(inv ->
                byId.values().stream().anyMatch(p -> p.getBookingId().equals(inv.getArgument(0))
                        && p.getStatus() == inv.getArgument(1)));

        // ---- a single pending booking, owned by USER_ID, with a frozen amount
        Booking booking = pendingBooking();
        BookingRepository bookingRepository = mock(BookingRepository.class);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(java.util.Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(java.util.Optional.of(booking));

        SeatRepository seatRepository = mock(SeatRepository.class);
        when(seatRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of());

        // ---- count gateway charges and seat confirmations
        AtomicInteger charges = new AtomicInteger();
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.charge(any())).thenAnswer(inv -> {
            charges.incrementAndGet();
            return PaymentGateway.PaymentResult.ok("PAY-OK");
        });

        AtomicInteger confirms = new AtomicInteger();
        SeatReservationService reservationService = mock(SeatReservationService.class);
        doAnswer(inv -> {
            confirms.incrementAndGet();
            booking.confirm(Instant.now()); // flip so any later confirm guard would see CONFIRMED
            return null;
        }).when(reservationService).confirm(anyLong(), anyLong(), any());

        PaymentService paymentService = new PaymentService(
                paymentRepository, bookingRepository, seatRepository, reservationService);
        BookingProperties properties = new BookingProperties(5,
                BookingProperties.LockingStrategy.PESSIMISTIC, 5, 10, false);
        BookingService bookingService = new BookingService(reservationService, paymentService,
                bookingRepository, seatRepository, paymentRepository, gateway,
                mock(RedisLockService.class), properties,
                mock(com.seatvault.repository.EventRepository.class), mock(QueueService.class), mock(CheckoutService.class));

        User user = user();

        // ---- fire THREADS concurrent pay() calls with the SAME idempotency key
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger handledFailures = new AtomicInteger();

        try {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        BookingResponse r = bookingService.pay(user, BOOKING_ID, new PaymentRequest("CARD", KEY));
                        if (r != null) successes.incrementAndGet();
                    } catch (RuntimeException expected) {
                        // losers that replay an in-flight attempt get a clean ConflictException
                        handledFailures.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("all workers finished").isTrue();
        } finally {
            pool.shutdownNow();
        }

        // The core guarantees: charged once, confirmed once — no double-charge, no double-confirm.
        assertThat(charges.get()).as("gateway charged exactly once").isEqualTo(1);
        assertThat(confirms.get()).as("booking confirmed exactly once").isEqualTo(1);
        // Every thread got a definitive outcome; at least one observed success.
        assertThat(successes.get() + handledFailures.get()).isEqualTo(THREADS);
        assertThat(successes.get()).isGreaterThanOrEqualTo(1);
        // Exactly one INITIATED row was ever created for the key.
        assertThat(byId).hasSize(1);
    }

    private Booking pendingBooking() {
        Event event = new Event("Concert", "d", "Arena",
                Instant.now().plus(2, ChronoUnit.DAYS), 2);
        event.setId(100L);
        event.setCurrency("INR");
        Booking b = new Booking(user(), event, 2, Instant.now().plus(5, ChronoUnit.MINUTES),
                "INR", 25_000L, 0L);
        b.setId(BOOKING_ID);
        return b;
    }

    private static User user() {
        User u = new User("u@example.com", "h", "Racer", Role.USER);
        u.setId(USER_ID);
        return u;
    }
}
