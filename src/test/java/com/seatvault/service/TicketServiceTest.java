package com.seatvault.service;

import com.seatvault.config.DisplayFormat;
import com.seatvault.config.TicketProperties;
import com.seatvault.domain.Booking;
import com.seatvault.domain.Event;
import com.seatvault.domain.Role;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.domain.User;
import com.seatvault.dto.ticket.TicketVerifyResponse;
import com.seatvault.exception.BadRequestException;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock SeatRepository seatRepository;
    @Mock TicketCheckinService ticketCheckinService;
    @Mock TicketCheckinRepository ticketCheckinRepository;

    TicketService ticketService;

    @BeforeEach
    void setUp() {
        TicketProperties properties = new TicketProperties("a-unique-test-hmac-secret-0123456789");
        ticketService = new TicketService(properties, bookingRepository, seatRepository, ticketCheckinService,
                ticketCheckinRepository, new DisplayFormat("Asia/Kolkata"));
    }

    // ----------------------------------------------------------------- signing

    @Test
    void signAndVerifyRoundTrips() {
        String token = ticketService.signToken(10L, 20L, 30L);

        TicketPayload payload = ticketService.verifyToken(token);

        assertThat(payload.bookingId()).isEqualTo(10L);
        assertThat(payload.seatId()).isEqualTo(20L);
        assertThat(payload.eventId()).isEqualTo(30L);
    }

    @Test
    void verifyRejectsTamperedPayload() {
        String token = ticketService.signToken(10L, 20L, 30L);
        String[] parts = token.split("\\.");
        // Swap the payload for one claiming a different (still well-formed) seat id.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("10|999|30|1700000000".getBytes());
        String forged = forgedPayload + "." + parts[1];

        assertThatThrownBy(() -> ticketService.verifyToken(forged))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("tampered");
    }

    @Test
    void verifyRejectsMalformedToken() {
        assertThatThrownBy(() -> ticketService.verifyToken("not-a-valid-token"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void verifyRejectsNullToken() {
        assertThatThrownBy(() -> ticketService.verifyToken(null))
                .isInstanceOf(BadRequestException.class);
    }

    // ----------------------------------------------------------------- PDF

    @Test
    void generatesNonEmptyPdfWithHeader() {
        Event event = event();
        Booking booking = confirmedBooking(event);
        Seat seat = bookedSeat(event, booking);

        byte[] pdf = ticketService.generateTicketPdf(booking, event, List.of(seat));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    // ----------------------------------------------------------------- check-in orchestration

    @Test
    void verifyAndCheckInDelegatesToCheckinServiceOnFirstScan() {
        String token = ticketService.signToken(50L, 20L, 1L);
        TicketVerifyResponse expected =
                new TicketVerifyResponse(true, 50L, 20L, "A1", "Concert", false, Instant.now(),
                        "SV-TEST-0001", null, "Jane", "scanner@example.com");
        when(ticketCheckinService.attemptCheckIn(any(TicketPayload.class), org.mockito.ArgumentMatchers.eq("scanner@example.com")))
                .thenReturn(expected);

        TicketVerifyResponse response = ticketService.verifyAndCheckIn(token, 1L, "scanner@example.com");

        assertThat(response).isSameAs(expected);
    }

    @Test
    void verifyAndCheckInReplaysOnRaceLostAgainstConcurrentScan() {
        String token = ticketService.signToken(50L, 20L, 1L);
        when(ticketCheckinService.attemptCheckIn(any(TicketPayload.class), any()))
                .thenThrow(new DataIntegrityViolationException("uq_ticket_checkins_seat"));
        TicketVerifyResponse replay =
                new TicketVerifyResponse(true, 50L, 20L, "A1", "Concert", true, Instant.now().minusSeconds(30),
                        "SV-TEST-0001", null, "Jane", "gate-1@example.com");
        when(ticketCheckinService.replayCheckIn(any(TicketPayload.class))).thenReturn(replay);

        TicketVerifyResponse response = ticketService.verifyAndCheckIn(token, 1L, "scanner@example.com");

        assertThat(response).isSameAs(replay);
    }

    @Test
    void verifyAndCheckInRefusesAGenuineTicketForAnotherEventWithoutCheckingIn() {
        String token = ticketService.signToken(50L, 20L, 1L); // event 1

        assertThatThrownBy(() -> ticketService.verifyAndCheckIn(token, 2L, "scanner@example.com"))
                .isInstanceOf(com.seatvault.exception.ConflictException.class)
                .hasMessageContaining("different event");
        org.mockito.Mockito.verifyNoInteractions(ticketCheckinService);
    }

    // ----------------------------------------------------------------- download

    @Test
    void generateForBookingRejectsNonOwnerNonAdmin() {
        Event event = event();
        Booking booking = confirmedBooking(event);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        User stranger = user(999L, Role.USER);

        assertThatThrownBy(() -> ticketService.generateForBooking(stranger, booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void generateForBookingAllowsAdminForAnyBooking() {
        Event event = event();
        Booking booking = confirmedBooking(event);
        Seat seat = bookedSeat(event, booking);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(seatRepository.findByBookingId(booking.getId())).thenReturn(List.of(seat));

        User admin = user(999L, Role.ADMIN);

        byte[] pdf = ticketService.generateForBooking(admin, booking.getId());

        assertThat(pdf).isNotEmpty();
    }

    // ----------------------------------------------------------------- fixtures

    private static Event event() {
        Event event = new Event("Concert", "d", "Arena", Instant.now().plusSeconds(3600), 10);
        event.setId(1L);
        return event;
    }

    private static Booking confirmedBooking(Event event) {
        User owner = user(7L, Role.USER);
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

    private static User user(Long id, Role role) {
        User u = new User("u" + id + "@example.com", "h", "User " + id, role);
        u.setId(id);
        return u;
    }
}
