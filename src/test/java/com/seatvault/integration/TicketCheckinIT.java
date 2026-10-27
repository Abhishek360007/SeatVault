package com.seatvault.integration;

import com.seatvault.domain.Event;
import com.seatvault.domain.Role;
import com.seatvault.domain.Seat;
import com.seatvault.domain.User;
import com.seatvault.dto.booking.CheckoutResponse;
import com.seatvault.dto.booking.VerifyPaymentRequest;
import com.seatvault.dto.ticket.TicketPass;
import com.seatvault.dto.ticket.TicketVerifyResponse;
import com.seatvault.exception.BadRequestException;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.payment.SimulatedPaymentGateway;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.repository.UserRepository;
import com.seatvault.service.CheckoutService;
import com.seatvault.service.SeatReservationService;
import com.seatvault.service.TicketCheckinService;
import com.seatvault.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QR tickets and gate check-in against real Postgres: each seat gets its own
 * signed QR, a ticket is admitted exactly once (even with simultaneous scans),
 * and forged or wrong-event tickets are refused without being recorded.
 */
class TicketCheckinIT extends AbstractIntegrationTest {

    @Autowired TicketService ticketService;
    @Autowired TicketCheckinService checkinService;
    @Autowired CheckoutService checkoutService;
    @Autowired SeatReservationService reservationService;
    @Autowired SimulatedPaymentGateway gateway;
    @Autowired EventRepository eventRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository userRepository;

    private User alice;
    private User mallory;
    private Event event;
    private Event otherEvent;
    private Long bookingId;

    @BeforeEach
    void seed() {
        deleteBookingDependents();
        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();

        alice = userRepository.save(new User("alice@test.local", "hash", "Alice", Role.USER));
        mallory = userRepository.save(new User("mallory@test.local", "hash", "Mallory", Role.USER));
        event = eventWithSeats("Gig", 3);
        otherEvent = eventWithSeats("Other gig", 1);

        List<Long> seats = seatRepository.findByEventIdOrderBySeatLabel(event.getId()).stream()
                .limit(2).map(Seat::getId).toList();
        bookingId = reservationService.reservePessimistic(alice, event.getId(), seats,
                Instant.now().plus(5, ChronoUnit.MINUTES)).id();
        CheckoutResponse co = checkoutService.startCheckout(alice, bookingId);
        var paid = gateway.simulateCheckout(co.orderId(), SimulatedPaymentGateway.Outcome.SUCCESS, "CARD");
        checkoutService.verify(alice, bookingId, new VerifyPaymentRequest(paid.orderId(), paid.paymentId(), paid.signature()));
    }

    @Test
    void ownerGetsOneDistinctQrPassPerSeat() {
        List<TicketPass> passes = ticketService.passesForBooking(alice, bookingId);

        assertThat(passes).extracting(TicketPass::seatLabel).containsExactly("A1", "A2");
        assertThat(passes).extracting(TicketPass::token).doesNotHaveDuplicates();
        assertThat(passes).allSatisfy(p -> {
            assertThat(p.qrPng()).startsWith("data:image/png;base64,");
            assertThat(p.checkedInAt()).isNull();
        });
        assertThatThrownBy(() -> ticketService.passesForBooking(mallory, bookingId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aTicketIsAdmittedOnceAndThenShowsAsAlreadyUsed() {
        TicketPass pass = ticketService.passesForBooking(alice, bookingId).get(0);

        TicketVerifyResponse first = ticketService.verifyAndCheckIn(pass.token(), event.getId(), "gate-1@test.local");
        TicketVerifyResponse again = ticketService.verifyAndCheckIn(pass.token(), event.getId(), "gate-2@test.local");

        assertThat(first.alreadyCheckedIn()).isFalse();
        assertThat(first.holderName()).isEqualTo("Alice");
        assertThat(again.alreadyCheckedIn()).isTrue();
        assertThat(again.checkedInBy()).isEqualTo("gate-1@test.local"); // who let it in first
        assertThat(ticketService.passesForBooking(alice, bookingId).get(0).checkedInAt()).isNotNull();
        assertThat(checkinService.summary(event.getId()).checkedIn()).isEqualTo(1);
        assertThat(checkinService.summary(event.getId()).ticketsSold()).isEqualTo(2);
    }

    @Test
    void simultaneousScansOfOneTicketAdmitExactlyOnce() throws Exception {
        String token = ticketService.passesForBooking(alice, bookingId).get(0).token();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TicketVerifyResponse>> scans = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int gate = i;
            scans.add(pool.submit(() -> {
                go.await();
                return ticketService.verifyAndCheckIn(token, event.getId(), "gate-" + gate + "@test.local");
            }));
        }
        go.countDown();
        int admitted = 0;
        for (Future<TicketVerifyResponse> f : scans) {
            if (!f.get().alreadyCheckedIn()) {
                admitted++;
            }
        }
        pool.shutdown();

        assertThat(admitted).isEqualTo(1);
        assertThat(checkinService.summary(event.getId()).checkedIn()).isEqualTo(1);
    }

    @Test
    void forgedAndWrongEventTicketsAreRefusedAndNotRecorded() {
        String token = ticketService.passesForBooking(alice, bookingId).get(0).token();
        String forgedSeat = ticketService.signToken(bookingId, 999_999L, event.getId()).split("\\.")[0]
                + "." + token.split("\\.")[1]; // someone else's signature on an edited payload

        assertThatThrownBy(() -> ticketService.verifyAndCheckIn(forgedSeat, event.getId(), "gate@test.local"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ticketService.verifyAndCheckIn("not-a-ticket", event.getId(), "gate@test.local"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ticketService.verifyAndCheckIn(token, otherEvent.getId(), "gate@test.local"))
                .isInstanceOf(ConflictException.class);

        assertThat(checkinService.summary(event.getId()).checkedIn()).isZero();
        // ...and the genuine ticket still works at its own event.
        assertThat(ticketService.verifyAndCheckIn(token, event.getId(), "gate@test.local").alreadyCheckedIn()).isFalse();
    }

    @Test
    void theTicketPdfHasOnePagePerSeat() {
        byte[] pdf = ticketService.generateForBooking(alice, bookingId);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1))
                .containsPattern("/Count 2\\b");
    }

    private Event eventWithSeats(String title, int seats) {
        Event e = new Event(title, "desc", "Hall", Instant.now().plus(2, ChronoUnit.DAYS), seats);
        e.setBasePriceMinor(10_000);
        for (int i = 1; i <= seats; i++) {
            e.addSeat(new Seat(e, "A" + i));
        }
        return eventRepository.save(e);
    }
}
