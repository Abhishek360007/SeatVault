package com.seatvault.integration;

import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.domain.Event;
import com.seatvault.domain.Role;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.domain.User;
import com.seatvault.dto.booking.BookingResponse;
import com.seatvault.dto.event.SeatResponse;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.SeatUnavailableException;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.repository.UserRepository;
import com.seatvault.service.EventService;
import com.seatvault.service.HoldReleaseService;
import com.seatvault.service.SeatReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hold lifecycle against real Postgres: a lapsed hold must be takeable straight
 * away (reclaimed on contact, not only by the sweep), the lapsed booking must
 * end up EXPIRED and unpayable, and a live hold must still block everyone else.
 */
class HoldExpiryIT extends AbstractIntegrationTest {

    @Autowired SeatReservationService reservationService;
    @Autowired HoldReleaseService holdReleaseService;
    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository userRepository;
    @Autowired CacheManager cacheManager;

    private User alice;
    private User bob;
    private Event event;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        deleteBookingDependents();
        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());

        alice = userRepository.save(new User("alice@test.local", "hash", "Alice", Role.USER));
        bob = userRepository.save(new User("bob@test.local", "hash", "Bob", Role.USER));
        Event e = new Event("Show", "desc", "Hall", Instant.now().plus(2, ChronoUnit.DAYS), 2);
        e.addSeat(new Seat(e, "A1"));
        e.addSeat(new Seat(e, "A2"));
        event = eventRepository.save(e);
        seatIds = seatRepository.findByEventIdOrderBySeatLabel(event.getId()).stream().map(Seat::getId).toList();
    }

    @Test
    void liveHoldBlocksOtherUsers() {
        reservationService.reservePessimistic(alice, event.getId(), List.of(seatIds.get(0)), inMinutes(5));

        assertThatThrownBy(() -> reservationService.reservePessimistic(
                bob, event.getId(), List.of(seatIds.get(0)), inMinutes(5)))
                .isInstanceOf(SeatUnavailableException.class);
        assertThatThrownBy(() -> reservationService.reserveOptimistic(
                bob, event.getId(), List.of(seatIds.get(0)), inMinutes(5)))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    void lapsedHoldIsReclaimedOnContactBeforeTheSweepRuns() {
        BookingResponse stale = reservationService.reservePessimistic(
                alice, event.getId(), seatIds, Instant.now().minusSeconds(1)); // already lapsed

        BookingResponse fresh = reservationService.reservePessimistic(
                bob, event.getId(), List.of(seatIds.get(0)), inMinutes(5));

        Seat taken = seatRepository.findById(seatIds.get(0)).orElseThrow();
        assertThat(taken.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(taken.getBookingId()).isEqualTo(fresh.id());
        assertThat(taken.getHeldByUserId()).isEqualTo(bob.getId());
        Booking lapsed = bookingRepository.findById(stale.id()).orElseThrow();
        assertThat(lapsed.getStatus()).isEqualTo(BookingStatus.EXPIRED);

        // Alice's lapsed booking can no longer be paid for.
        assertThatThrownBy(() -> reservationService.confirm(stale.id(), alice.getId(), Instant.now()))
                .isInstanceOf(ConflictException.class);

        // Her other lapsed seat is still marked HELD until the sweep frees it.
        assertThat(seatRepository.findById(seatIds.get(1)).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(holdReleaseService.releaseBatch(100, Instant.now())).isEqualTo(1);
        assertThat(seatRepository.findById(seatIds.get(1)).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void lapsedHoldIsAlsoReclaimedOnTheOptimisticPath() {
        reservationService.reservePessimistic(alice, event.getId(), List.of(seatIds.get(0)), Instant.now().minusSeconds(1));

        BookingResponse fresh = reservationService.reserveOptimistic(
                bob, event.getId(), List.of(seatIds.get(0)), inMinutes(5));

        assertThat(seatRepository.findById(seatIds.get(0)).orElseThrow().getBookingId()).isEqualTo(fresh.id());
    }

    @Test
    void seatMapReportsLapsedHoldsAsAvailable() {
        reservationService.reservePessimistic(alice, event.getId(), List.of(seatIds.get(0)), Instant.now().minusSeconds(1));
        reservationService.reservePessimistic(bob, event.getId(), List.of(seatIds.get(1)), inMinutes(5));
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());

        List<SeatResponse> seats = eventService.getSeats(event.getId(), null);

        assertThat(seats).extracting(SeatResponse::seatLabel, SeatResponse::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A1", SeatStatus.AVAILABLE),
                        org.assertj.core.groups.Tuple.tuple("A2", SeatStatus.HELD));
    }

    private static Instant inMinutes(int minutes) {
        return Instant.now().plus(minutes, ChronoUnit.MINUTES);
    }
}
