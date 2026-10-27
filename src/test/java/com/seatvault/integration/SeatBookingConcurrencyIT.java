package com.seatvault.integration;

import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.domain.Event;
import com.seatvault.domain.Role;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.domain.User;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.repository.UserRepository;
import com.seatvault.service.SeatReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE non-negotiable test (build spec, Phase 8): fire many concurrent reservation
 * attempts at the SAME seat and prove the system never oversells — exactly one
 * caller wins, everyone else fails cleanly. We run it against both locking
 * strategies through {@link SeatReservationService}, plus a fairness check that N
 * threads over N seats produce exactly N winners.
 *
 * <p>Each reservation runs in its own transaction (the service is called across the
 * Spring proxy from worker threads), against the real Postgres container — this is
 * the genuine race, not a simulation.
 */
class SeatBookingConcurrencyIT extends AbstractIntegrationTest {

    private static final int THREADS = 24;

    @Autowired SeatReservationService reservationService;
    @Autowired EventRepository eventRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void cleanSlate() {
        deleteBookingDependents();
        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void contextLoads() {
        // Smoke test: the full application context wires up against real Postgres + Redis.
        assertThat(reservationService).isNotNull();
    }

    @Test
    @DisplayName("Pessimistic: 24 threads, 1 seat → exactly 1 winner, no oversell")
    void pessimisticNeverOversellsOneSeat() {
        assertExactlyOneWinnerForSingleSeat(reservationService::reservePessimistic);
    }

    @Test
    @DisplayName("Optimistic: 24 threads, 1 seat → exactly 1 winner, no oversell")
    void optimisticNeverOversellsOneSeat() {
        assertExactlyOneWinnerForSingleSeat(reservationService::reserveOptimistic);
    }

    @Test
    @DisplayName("Pessimistic: 24 threads, 4 seats → exactly 4 winners")
    void pessimisticAllocatesEachSeatToExactlyOneWinner() {
        User user = newUser("multi@test.com");
        Event event = newEventWithSeats("Festival", 4);
        List<Long> seatIds = seatIdsOf(event);

        AtomicInteger successes = new AtomicInteger();
        // Each thread tries to grab ONE seat, round-robined across the 4 seats, so
        // ~6 threads contend for each seat. Exactly one wins per seat → 4 total.
        runConcurrently(THREADS, i -> {
            Long seatId = seatIds.get(i % seatIds.size());
            try {
                reservationService.reservePessimistic(user, event.getId(), List.of(seatId),
                        Instant.now().plus(5, ChronoUnit.MINUTES));
                successes.incrementAndGet();
            } catch (RuntimeException expected) {
                // losers fail cleanly
            }
        });

        assertThat(successes.get()).isEqualTo(4);
        assertThat(seatRepository.countByEventIdAndStatus(event.getId(), SeatStatus.HELD)).isEqualTo(4);
        assertThat(confirmedOrPending(BookingStatus.PENDING)).isEqualTo(4);
    }

    // ---------------------------------------------------------------- helpers

    /** Strategy under test: (user, eventId, seatIds, expiresAt) → reservation. */
    @FunctionalInterface
    private interface ReserveFn {
        void reserve(User user, Long eventId, List<Long> seatIds, Instant expiresAt);
    }

    private void assertExactlyOneWinnerForSingleSeat(ReserveFn reserve) {
        User user = newUser("racer@test.com");
        Event event = newEventWithSeats("Sellout", 1);
        Long seatId = seatIdsOf(event).get(0);

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        runConcurrently(THREADS, i -> {
            try {
                reserve.reserve(user, event.getId(), List.of(seatId),
                        Instant.now().plus(5, ChronoUnit.MINUTES));
                successes.incrementAndGet();
            } catch (RuntimeException expected) {
                failures.incrementAndGet();
            }
        });

        assertThat(successes.get()).as("exactly one winner").isEqualTo(1);
        assertThat(failures.get()).as("everyone else fails cleanly").isEqualTo(THREADS - 1);

        Seat seat = seatRepository.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(confirmedOrPending(BookingStatus.PENDING)).as("only one booking persisted").isEqualTo(1);
    }

    /** Runs {@code task} on {@code n} threads released simultaneously for max contention. */
    private void runConcurrently(int n, java.util.function.IntConsumer task) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        task.accept(idx);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            start.countDown(); // fire all at once
            // done.countDown() runs in each worker's finally, AFTER its @Transactional
            // reservation has committed/rolled back — so once this latch hits zero every
            // seat row-lock has been released. We deliberately do NOT shutdownNow() on the
            // happy path: interrupting a thread blocked in SELECT ... FOR UPDATE can leave
            // a pooled connection holding a seat lock in an open transaction, which then
            // deadlocks the next test's @BeforeEach deleteAll() (Postgres lock wait, no
            // timeout). Graceful shutdown lets the now-idle threads return their connections.
            assertThat(done.await(60, TimeUnit.SECONDS)).as("all workers finished").isTrue();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).as("pool drained").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new IllegalStateException(e);
        }
    }

    private long confirmedOrPending(BookingStatus status) {
        return bookingRepository.findAll().stream().filter(b -> b.getStatus() == status).count();
    }

    private User newUser(String email) {
        return userRepository.save(new User(email, "hash", "Racer", Role.USER));
    }

    private Event newEventWithSeats(String title, int seatCount) {
        Event event = new Event(title, "desc", "Arena", Instant.now().plus(2, ChronoUnit.DAYS), seatCount);
        for (int i = 1; i <= seatCount; i++) {
            event.addSeat(new Seat(event, "A" + i));
        }
        return eventRepository.save(event); // cascades the seats
    }

    private List<Long> seatIdsOf(Event event) {
        List<Long> ids = new ArrayList<>();
        seatRepository.findByEventIdOrderBySeatLabel(event.getId()).forEach(s -> ids.add(s.getId()));
        return ids;
    }
}
