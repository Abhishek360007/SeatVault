package com.seatvault.notification;

import com.seatvault.config.DisplayFormat;
import com.seatvault.domain.Booking;
import com.seatvault.domain.Event;
import com.seatvault.domain.Payment;
import com.seatvault.domain.Seat;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.PaymentRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.service.TicketService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an outbox row into a ready-to-send email: HTML from
 * {@code templates/email/<type>.html}, a plain-text alternative for clients that
 * don't render HTML, and the PDF tickets where they belong. Rendered from the
 * current database state, so it always reflects the final booking and payment.
 */
@Component
public class EmailComposer {

    /** The booking an email refers to no longer exists: retrying can't help. */
    public static class MissingBookingException extends RuntimeException {
        public MissingBookingException(Long bookingId) {
            super("Booking " + bookingId + " no longer exists");
        }
    }

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final PaymentRepository paymentRepository;
    private final TicketService ticketService;
    private final TemplateEngine templates;
    private final JavaMailSender mailSender;
    private final DisplayFormat display;
    private final String from;
    private final String baseUrl;

    public EmailComposer(BookingRepository bookingRepository,
                         SeatRepository seatRepository,
                         PaymentRepository paymentRepository,
                         TicketService ticketService,
                         TemplateEngine templates,
                         JavaMailSender mailSender,
                         DisplayFormat display,
                         @Value("${app.notifications.from}") String from,
                         @Value("${app.public-base-url}") String baseUrl) {
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.paymentRepository = paymentRepository;
        this.ticketService = ticketService;
        this.templates = templates;
        this.mailSender = mailSender;
        this.display = display;
        this.from = from;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Transactional(readOnly = true)
    public MimeMessage compose(NotificationType type, Long bookingId, String recipient) throws MessagingException {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new MissingBookingException(bookingId));
        Event event = booking.getEvent();
        List<Seat> seats = seatRepository.findByBookingId(bookingId).stream()
                .sorted(Comparator.comparing(Seat::getSeatLabel)).toList();
        Payment payment = paymentRepository.findTopByBookingIdOrderByIdDesc(bookingId).orElse(null);

        Map<String, Object> model = model(type, booking, event, seats, payment);
        Context context = new Context();
        context.setVariables(model);
        String html = templates.process("email/" + type.name().toLowerCase().replace('_', '-'), context);

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(recipient);
        helper.setSubject((String) model.get("subject"));
        helper.setText(plainText(type, model), html);
        if (attachesTickets(type) && !seats.isEmpty()) {
            byte[] pdf = ticketService.generateTicketPdf(booking, event, seats);
            helper.addAttachment("SeatVault-" + booking.getReference() + ".pdf",
                    new ByteArrayResource(pdf), "application/pdf");
        }
        message.setHeader("X-SeatVault-Notification", type.name());
        return message;
    }

    private static boolean attachesTickets(NotificationType type) {
        return type == NotificationType.BOOKING_CONFIRMED || type == NotificationType.EVENT_REMINDER;
    }

    private Map<String, Object> model(NotificationType type, Booking booking, Event event,
                                      List<Seat> seats, Payment payment) {
        String amount = display.money(booking.getAmountMinor(), booking.getCurrency());
        String when = display.dateTime(event.getEventDateTime());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", switch (type) {
            case BOOKING_CONFIRMED -> "Booking confirmed: " + event.getTitle() + " · " + booking.getReference();
            case PAYMENT_RECEIPT -> "Payment receipt " + booking.getReference() + " · " + amount;
            case BOOKING_CANCELLED -> "Booking cancelled: " + event.getTitle() + " · " + booking.getReference();
            case PAYMENT_REFUNDED -> "Refund issued: " + amount + " for " + booking.getReference();
            case EVENT_REMINDER -> "Reminder: " + event.getTitle() + " is on " + when;
        });
        m.put("name", firstName(booking.getUser().getFullName()));
        m.put("reference", booking.getReference());
        m.put("eventTitle", event.getTitle());
        m.put("venue", event.getCity() == null ? event.getVenue() : event.getVenue() + ", " + event.getCity());
        m.put("date", display.date(event.getEventDateTime()));
        m.put("time", display.time(event.getEventDateTime()));
        m.put("seatLabels", String.join(", ", seats.stream().map(Seat::getSeatLabel).toList()));
        m.put("seatCount", booking.getSeatCount());
        m.put("lines", seats.stream().map(s -> Map.of(
                "label", s.getSeatLabel(),
                "tier", s.getTierName() == null ? "" : s.getTierName(),
                "price", display.money(s.effectivePriceMinor(), booking.getCurrency()))).toList());
        m.put("subtotal", display.money(booking.getAmountMinor() - booking.getFeeMinor(), booking.getCurrency()));
        m.put("fee", booking.getFeeMinor() == 0 ? "Free" : display.money(booking.getFeeMinor(), booking.getCurrency()));
        m.put("total", amount);
        m.put("paidAt", display.dateTime(booking.getConfirmedAt() != null ? booking.getConfirmedAt()
                : payment != null ? payment.getUpdatedAt() : null));
        m.put("paymentProvider", payment == null || payment.getProvider() == null ? "Card"
                : switch (payment.getProvider()) {
                    case "razorpay" -> "Razorpay";
                    case "simulated" -> "Test payment";
                    default -> payment.getProvider();
                });
        m.put("paymentId", payment == null ? "—" : payment.getProviderReference() == null ? "—" : payment.getProviderReference());
        m.put("refundReason", payment == null ? "" : payment.getFailureReason());
        m.put("ticketsUrl", baseUrl + "/?tickets");
        m.put("eventUrl", baseUrl + "/?event=" + event.getId());
        String poster = com.seatvault.domain.EventImages.posterFor(event);
        m.put("eventImage", poster.startsWith("https://") ? poster : baseUrl + poster);
        m.put("baseUrl", baseUrl);
        return m;
    }

    /** The plain-text part: the essentials, readable in any mail client. */
    private static String plainText(NotificationType type, Map<String, Object> m) {
        String intro = switch (type) {
            case BOOKING_CONFIRMED -> "Your booking is confirmed! Your tickets are attached as a PDF.";
            case PAYMENT_RECEIPT -> "Thanks for your payment. Here is your receipt.";
            case BOOKING_CANCELLED -> "Your booking has been cancelled and the seats released. You were not charged.";
            case PAYMENT_REFUNDED -> "Your seat hold ended before the payment completed, so we refunded it in full. "
                    + "Refunds usually reach your account in 5-7 working days.";
            case EVENT_REMINDER -> "Your event is coming up soon. Your tickets are attached again for convenience.";
        };
        return "Hi " + m.get("name") + ",\n\n" + intro + "\n\n"
                + "Booking reference: " + m.get("reference") + "\n"
                + "Event: " + m.get("eventTitle") + "\n"
                + "When: " + m.get("date") + ", " + m.get("time") + "\n"
                + "Where: " + m.get("venue") + "\n"
                + "Seats: " + m.get("seatLabels") + "\n"
                + "Total: " + m.get("total") + "\n\n"
                + "My tickets: " + m.get("ticketsUrl") + "\n\n— SeatVault";
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        return fullName.trim().split("\\s+")[0];
    }
}
