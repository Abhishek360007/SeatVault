package com.seatvault.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatvault.domain.Booking;
import com.seatvault.domain.Event;
import com.seatvault.domain.Role;
import com.seatvault.domain.Seat;
import com.seatvault.domain.User;
import com.seatvault.dto.booking.CheckoutResponse;
import com.seatvault.dto.booking.VerifyPaymentRequest;
import com.seatvault.notification.EmailComposer;
import com.seatvault.notification.EmailDispatcher;
import com.seatvault.notification.EventReminderScheduler;
import com.seatvault.notification.Notification;
import com.seatvault.notification.NotificationRepository;
import com.seatvault.notification.NotificationService;
import com.seatvault.notification.NotificationType;
import com.seatvault.payment.SimulatedPaymentGateway;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.repository.UserRepository;
import com.seatvault.service.BookingService;
import com.seatvault.service.CheckoutService;
import com.seatvault.service.SeatReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Email notifications end to end: booking changes write outbox rows in their own
 * transaction, the dispatcher delivers them over real SMTP (to a Mailpit
 * container), failures are retried with backoff, and nothing is sent twice.
 */
class NotificationIT extends AbstractIntegrationTest {

    @Autowired CheckoutService checkoutService;
    @Autowired BookingService bookingService;
    @Autowired SeatReservationService reservationService;
    @Autowired SimulatedPaymentGateway gateway;
    @Autowired EmailDispatcher dispatcher;
    @Autowired EmailComposer composer;
    @Autowired EventReminderScheduler reminders;
    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notifications;
    @Autowired EventRepository eventRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private User alice;
    private Event event;

    @BeforeEach
    void seed() throws Exception {
        deleteBookingDependents();
        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();
        mailpit("DELETE", "/api/v1/messages");

        alice = userRepository.save(new User("alice@test.local", "hash", "Alice Sharma", Role.USER));
        Event e = new Event("Indie Night", "desc", "Blue Frog", Instant.now().plus(3, ChronoUnit.DAYS), 4);
        e.setCity("Mumbai");
        e.setBasePriceMinor(75_000);
        e.setConvenienceFeeMinor(2_500);
        for (int i = 1; i <= 4; i++) {
            e.addSeat(new Seat(e, "A" + i));
        }
        event = eventRepository.save(e);
    }

    @Test
    void confirmedBookingSendsConfirmationWithPdfTicketsAndAReceipt() throws Exception {
        Long bookingId = confirmBooking(2);
        String reference = bookingRepository.findById(bookingId).orElseThrow().getReference();

        assertThat(types(bookingId)).containsExactlyInAnyOrder(
                NotificationType.BOOKING_CONFIRMED, NotificationType.PAYMENT_RECEIPT);
        assertThat(dispatcher.dispatchBatch(Instant.now())).isEqualTo(2);

        JsonNode inbox = mailpit("GET", "/api/v1/messages");
        assertThat(inbox.path("total").asInt()).isEqualTo(2);
        JsonNode confirmation = message("Booking confirmed");
        assertThat(confirmation.path("To").get(0).path("Address").asText()).isEqualTo("alice@test.local");
        assertThat(confirmation.path("HTML").asText()).contains(reference, "Indie Night", "Blue Frog, Mumbai", "A1, A2");
        assertThat(confirmation.path("Text").asText()).contains(reference);
        assertThat(confirmation.path("Attachments")).singleElement().satisfies(a -> {
            assertThat(a.path("FileName").asText()).isEqualTo("SeatVault-" + reference + ".pdf");
            assertThat(a.path("ContentType").asText()).isEqualTo("application/pdf");
        });
        JsonNode receipt = message("Payment receipt");
        assertThat(receipt.path("HTML").asText()).contains("₹1,550.00", "pay_sim_", "Test payment");

        assertThat(notifications.findByBookingIdOrderById(bookingId))
                .allSatisfy(n -> assertThat(n.getStatus()).isEqualTo(Notification.Status.SENT));
        assertThat(dispatcher.dispatchBatch(Instant.now())).isZero(); // nothing left to send
    }

    @Test
    void repeatedConfirmationSignalsQueueEachEmailOnce() {
        Long bookingId = confirmBooking(1);

        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            Booking booking = bookingRepository.findById(bookingId).orElseThrow();
            notificationService.enqueue(NotificationType.BOOKING_CONFIRMED, booking);
            notificationService.enqueue(NotificationType.BOOKING_CONFIRMED, booking);
        });

        assertThat(types(bookingId)).hasSize(2); // still just the confirmation + receipt
    }

    @Test
    void emailsAreOnlyQueuedInsideTheBookingsTransactionAndRollBackWithIt() {
        Long bookingId = confirmBooking(1);
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        jdbc.update("DELETE FROM notifications");

        assertThatThrownBy(() -> notificationService.enqueue(NotificationType.EVENT_REMINDER, booking))
                .isInstanceOf(IllegalTransactionStateException.class);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> {
            notificationService.enqueue(NotificationType.EVENT_REMINDER, bookingRepository.findById(bookingId).orElseThrow());
            s.setRollbackOnly(); // the booking change failed
        });
        assertThat(types(bookingId)).isEmpty();
    }

    @Test
    void cancellationSendsACancellationEmail() throws Exception {
        Long bookingId = reservationService.reservePessimistic(alice, event.getId(), seatIds(1),
                Instant.now().plus(5, ChronoUnit.MINUTES)).id();
        bookingService.cancel(alice, bookingId);

        assertThat(types(bookingId)).containsExactly(NotificationType.BOOKING_CANCELLED);
        dispatcher.dispatchBatch(Instant.now());
        assertThat(message("Booking cancelled").path("HTML").asText()).contains("nothing was charged");
    }

    @Test
    void lateRefundSendsARefundEmail() throws Exception {
        Long bookingId = reservationService.reservePessimistic(alice, event.getId(), seatIds(1),
                Instant.now().plus(5, ChronoUnit.MINUTES)).id();
        CheckoutResponse checkout = checkoutService.startCheckout(alice, bookingId);
        bookingService.cancel(alice, bookingId);
        var paid = gateway.simulateCheckout(checkout.orderId(), SimulatedPaymentGateway.Outcome.SUCCESS, "UPI");
        try {
            checkoutService.verify(alice, bookingId, new VerifyPaymentRequest(paid.orderId(), paid.paymentId(), paid.signature()));
        } catch (RuntimeException expected) {
            // refunded: a 409 for the customer, and an email
        }

        assertThat(types(bookingId)).containsExactlyInAnyOrder(
                NotificationType.BOOKING_CANCELLED, NotificationType.PAYMENT_REFUNDED);
        dispatcher.dispatchBatch(Instant.now());
        assertThat(message("Refund issued").path("HTML").asText()).contains("in full", "rfnd_sim_");
    }

    @Test
    void smtpOutageIsRetriedWithBackoffThenDelivered() throws Exception {
        Long bookingId = confirmBooking(1);
        JavaMailSenderImpl deadServer = new JavaMailSenderImpl();
        deadServer.setHost("127.0.0.1");
        deadServer.setPort(1); // nothing listens here
        EmailDispatcher outage = new EmailDispatcher(notifications, composer, deadServer, transactionManager, true, 20, 8);

        Instant t0 = Instant.now();
        assertThat(outage.dispatchBatch(t0)).isEqualTo(2);
        List<Notification> failed = notifications.findByBookingIdOrderById(bookingId);
        assertThat(failed).allSatisfy(n -> {
            assertThat(n.getStatus()).isEqualTo(Notification.Status.PENDING);
            assertThat(n.getAttempts()).isEqualTo(1);
            assertThat(n.getLastError()).isNotBlank();
            assertThat(n.getNextAttemptAt()).isAfter(t0.plusSeconds(25)); // backed off ~30s
        });
        assertThat(dispatcher.dispatchBatch(Instant.now())).isZero(); // not due yet

        assertThat(dispatcher.dispatchBatch(Instant.now().plusSeconds(60))).isEqualTo(2);
        assertThat(notifications.findByBookingIdOrderById(bookingId))
                .allSatisfy(n -> assertThat(n.getStatus()).isEqualTo(Notification.Status.SENT));
        assertThat(mailpit("GET", "/api/v1/messages").path("total").asInt()).isEqualTo(2);
    }

    @Test
    void anUndeliverableAddressFailsPermanentlyWithoutRetrying() {
        Long bookingId = confirmBooking(1);
        jdbc.update("DELETE FROM notifications");
        jdbc.update("""
                INSERT INTO notifications (type, booking_id, recipient, dedupe_key, status, attempts,
                                           next_attempt_at, created_at, updated_at)
                VALUES ('BOOKING_CONFIRMED', ?, 'not an address', 'x', 'PENDING', 0, now(), now(), now())""",
                bookingId);

        dispatcher.dispatchBatch(Instant.now());

        Notification n = notifications.findByBookingIdOrderById(bookingId).get(0);
        assertThat(n.getStatus()).isEqualTo(Notification.Status.FAILED);
        assertThat(n.getAttempts()).isEqualTo(1);
    }

    @Test
    void remindersGoToBookingsMadeBeforeTheWindowOnly() {
        Long early = confirmBooking(1);
        Long late = confirmBooking(1);
        jdbc.update("UPDATE events SET event_datetime = now() + interval '10 hours' WHERE id = ?", event.getId());
        jdbc.update("UPDATE bookings SET confirmed_at = now() - interval '3 days' WHERE id = ?", early);

        assertThat(reminders.queueReminders(Instant.now())).isEqualTo(1);
        reminders.queueReminders(Instant.now()); // a second sweep is harmless

        assertThat(types(early)).contains(NotificationType.EVENT_REMINDER);
        assertThat(types(late)).doesNotContain(NotificationType.EVENT_REMINDER);
        assertThat(types(early).stream().filter(t -> t == NotificationType.EVENT_REMINDER)).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private Long confirmBooking(int seats) {
        List<Long> free = seatRepository.findByEventIdOrderBySeatLabel(event.getId()).stream()
                .filter(Seat::isAvailable).limit(seats).map(Seat::getId).toList();
        Long bookingId = reservationService.reservePessimistic(alice, event.getId(), free,
                Instant.now().plus(5, ChronoUnit.MINUTES)).id();
        CheckoutResponse checkout = checkoutService.startCheckout(alice, bookingId);
        var paid = gateway.simulateCheckout(checkout.orderId(), SimulatedPaymentGateway.Outcome.SUCCESS, "CARD");
        checkoutService.verify(alice, bookingId, new VerifyPaymentRequest(paid.orderId(), paid.paymentId(), paid.signature()));
        return bookingId;
    }

    private List<Long> seatIds(int n) {
        return seatRepository.findByEventIdOrderBySeatLabel(event.getId()).stream().limit(n).map(Seat::getId).toList();
    }

    private List<NotificationType> types(Long bookingId) {
        return notifications.findByBookingIdOrderById(bookingId).stream().map(Notification::getType).toList();
    }

    /** The full message (HTML, text, attachments) whose subject starts with {@code prefix}. */
    private JsonNode message(String prefix) throws Exception {
        List<String> subjects = new ArrayList<>();
        for (JsonNode m : mailpit("GET", "/api/v1/messages").path("messages")) {
            subjects.add(m.path("Subject").asText());
            if (m.path("Subject").asText().startsWith(prefix)) {
                return mailpit("GET", "/api/v1/message/" + m.path("ID").asText());
            }
        }
        throw new AssertionError("No email starting with '" + prefix + "'; got " + subjects);
    }

    private JsonNode mailpit(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(mailpitApi() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        return body == null || body.isBlank() || !body.trim().startsWith("{") ? json.nullNode() : json.readTree(body);
    }
}
