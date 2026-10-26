package com.seatvault.service;

import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.domain.Event;
import com.seatvault.domain.Role;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.domain.TicketCheckin;
import com.seatvault.domain.User;
import com.seatvault.dto.ticket.TicketVerifyResponse;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.repository.TicketCheckinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketCheckinServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock SeatRepository seatRepository;
    @Mock TicketCheckinRepository ticketCheckinRepository;

    TicketCheckinService checkinService;

    @BeforeEach
    void setUp() {
        checkinService = new TicketCheckinService(bookingRepository, seatRepository, ticketCheckinRepository);
    }

    @Test
    void attemptCheckInRecordsFirstScan() {
        Event event = event();
        Booking booking = confirmedBooking(event);
        Seat seat = bookedSeat(event, booking);
        when(bookingRepository.findById(50L)).thenReturn(Optional.of(booking));
        when(seatRepository.findById(20L)).thenReturn(Optional.of(seat));
        when(ticketCheckinRepository.saveAndFlush(any(TicketCheckin.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketVerifyResponse response = checkinService.attemptCheckIn(
                new TicketPayload(50L, 20L, 1L, Instant.now()), "scanner@example.com");

        assertThat(response.valid()).isTrue();
        assertThat(response.alreadyCheckedIn()).isFalse();
        assertThat(response.seatLabel()).isEqualTo("A1");
    }

    @Test
    void attemptCheckInPropagatesDuplicateViolationUncaught() {
        // Must NOT be swallowed here — TicketService relies on this propagating so it
        // can retry in a fresh transaction (see TicketCheckinService's class comment).
        Event event = event();
        Booking booking = confirmedBooking(event);
        Seat seat = bookedSeat(event, booking);
        when(bookingRepository.findById(50L)).thenReturn(Optional.of(booking));
        when(seatRepository.findById(20L)).thenReturn(Optional.of(seat));
        when(ticketCheckinRepository.saveAndFlush(any(TicketCheckin.class)))
                .thenThrow(new DataIntegrityViolationException("uq_ticket_checkins_seat"));

        assertThatThrownBy(() -> checkinService.attemptCheckIn(
                new TicketPayload(50L, 20L, 1L, Instant.now()), "scanner@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptCheckInRejectsUnconfirmedBooking() {
        Event event = event();
        Booking booking = confirmedBooking(event);
        booking.setStatus(BookingStatus.PENDING);
        Seat seat = bookedSeat(event, booking);
        when(bookingRepository.findById(50L)).thenReturn(Optional.of(booking));
        when(seatRepository.findById(20L)).thenReturn(Optional.of(seat));

        assertThatThrownBy(() -> checkinService.attemptCheckIn(
                new TicketPayload(50L, 20L, 1L, Instant.now()), "scanner@example.com"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void attemptCheckInRejectsSeatNotBelongingToBooking() {
        Event event = event();
        Booking booking = confirmedBooking(event);
        Seat seat = bookedSeat(event, booking);
        seat.setBookingId(999L); // belongs to a different booking
        when(bookingRepository.findById(50L)).thenReturn(Optional.of(booking));
        when(seatRepository.findById(20L)).thenReturn(Optional.of(seat));

        assertThatThrownBy(() -> checkinService.attemptCheckIn(
                new TicketPayload(50L, 20L, 1L, Instant.now()), "scanner@example.com"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void replayCheckInReturnsExistingRecord() {
        Event event = event();
        Booking booking = confirmedBooking(event);
        Seat seat = bookedSeat(event, booking);
        Instant firstScan = Instant.now().minusSeconds(60);
        when(bookingRepository.findById(50L)).thenReturn(Optional.of(booking));
        when(seatRepository.findById(20L)).thenReturn(Optional.of(seat));
        when(ticketCheckinRepository.findBySeatId(20L))
                .thenReturn(Optional.of(new TicketCheckin(50L, 20L, firstScan, "other-scanner")));

        TicketVerifyResponse response = checkinService.replayCheckIn(new TicketPayload(50L, 20L, 1L, Instant.now()));

        assertThat(response.alreadyCheckedIn()).isTrue();
        assertThat(response.checkedInAt()).isEqualTo(firstScan);
    }

    @Test
    void attemptCheckInFailsWhenBookingMissing() {
        when(bookingRepository.findById(50L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checkinService.attemptCheckIn(
                new TicketPayload(50L, 20L, 1L, Instant.now()), "scanner@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static Event event() {
        Event event = new Event("Concert", "d", "Arena", Instant.now().plusSeconds(3600), 10);
        event.setId(1L);
        return event;
    }

    private static Booking confirmedBooking(Event event) {
        User owner = new User("u7@example.com", "h", "User 7", Role.USER);
        owner.setId(7L);
        Booking booking = new Booking(owner, event, 1, null);
        booking.setId(50L);
        booking.confirm(Instant.now());
        return booking;
    }

    private static Seat bookedSeat(Event event, Booking booking) {
        Seat seat = new Seat(event, "A1");
        seat.setId(20L);
        seat.setStatus(SeatStatus.BOOKED);
        seat.setBookingId(booking.getId());
        return seat;
    }
}
