package com.seatvault.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.seatvault.config.TicketProperties;
import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.domain.Event;
import com.seatvault.domain.Role;
import com.seatvault.domain.Seat;
import com.seatvault.domain.User;
import com.seatvault.dto.ticket.TicketVerifyResponse;
import com.seatvault.exception.BadRequestException;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * E-tickets: a PDF per booking (one section per seat) embedding a QR code that
 * encodes an HMAC-SHA256-signed token, plus gate-scanner verification.
 *
 * <h3>Token format</h3>
 * {@code <base64url(bookingId|seatId|eventId|issuedAtEpochSeconds)>.<base64url(HMAC-SHA256 signature)>}
 * — a compact, self-contained token: no DB lookup is needed to detect tampering,
 * only to check current booking/seat state and check-in history.
 *
 * <p>The actual check-in persistence lives in {@link TicketCheckinService}, a
 * separate transactional bean — see its class comment for why that split is
 * required (the same reason {@code PaymentService} is split from
 * {@code BookingService}: a unique-constraint race needs two transactions
 * invoked across the Spring proxy, not one method catching its own exception).
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final TicketProperties properties;
    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final TicketCheckinService ticketCheckinService;

    public TicketService(TicketProperties properties,
                         BookingRepository bookingRepository,
                         SeatRepository seatRepository,
                         TicketCheckinService ticketCheckinService) {
        this.properties = properties;
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.ticketCheckinService = ticketCheckinService;
    }

    // ============================ Signing ============================

    public String signToken(Long bookingId, Long seatId, Long eventId) {
        String payload = bookingId + "|" + seatId + "|" + eventId + "|" + Instant.now().getEpochSecond();
        String payloadB64 = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = ENCODER.encodeToString(hmac(payloadB64));
        return payloadB64 + "." + signature;
    }

    /** Verifies the signature and shape of a token. Never trusts the payload otherwise. */
    public TicketPayload verifyToken(String token) {
        if (token == null || token.indexOf('.') < 0) {
            throw new BadRequestException("Malformed ticket token");
        }
        int dot = token.indexOf('.');
        String payloadB64 = token.substring(0, dot);
        String signatureB64 = token.substring(dot + 1);

        byte[] expected = hmac(payloadB64);
        byte[] actual;
        try {
            actual = DECODER.decode(signatureB64);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Malformed ticket token");
        }
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            throw new BadRequestException("Ticket signature is invalid — this QR code has been tampered with");
        }

        String payload;
        try {
            payload = new String(DECODER.decode(payloadB64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Malformed ticket token");
        }
        String[] parts = payload.split("\\|");
        if (parts.length != 4) {
            throw new BadRequestException("Malformed ticket token");
        }
        try {
            return new TicketPayload(
                    Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                    Instant.ofEpochSecond(Long.parseLong(parts[3])));
        } catch (NumberFormatException ex) {
            throw new BadRequestException("Malformed ticket token");
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to compute ticket signature", ex);
        }
    }

    // ============================ PDF generation ============================

    /** Renders one PDF containing one page per seat, each with its own signed QR. */
    public byte[] generateTicketPdf(Booking booking, Event event, List<Seat> seats) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA, 12);

            for (int i = 0; i < seats.size(); i++) {
                Seat seat = seats.get(i);
                if (i > 0) {
                    document.newPage();
                }
                document.add(new Paragraph(event.getTitle(), titleFont));
                document.add(new Paragraph(event.getVenue() + " — " + event.getEventDateTime(), labelFont));
                document.add(new Paragraph("Seat: " + seat.getSeatLabel(), labelFont));
                document.add(new Paragraph("Booking: #" + booking.getId(), labelFont));
                document.add(new Paragraph(" "));

                String token = signToken(booking.getId(), seat.getId(), event.getId());
                document.add(Image.getInstance(qrCodePng(token)));
            }
            document.close();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate ticket PDF", ex);
        }
        return out.toByteArray();
    }

    /**
     * Owner-or-admin ticket download. Non-owners get 404 (not 403), matching the
     * rest of the API's "don't confirm another user's booking exists" convention.
     */
    @Transactional(readOnly = true)
    public byte[] generateForBooking(User caller, Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        boolean owner = booking.getUser().getId().equals(caller.getId());
        if (!owner && caller.getRole() != Role.ADMIN) {
            throw new ResourceNotFoundException("Booking not found");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ConflictException("A ticket is only available for a confirmed booking");
        }
        List<Seat> seats = seatRepository.findByBookingId(bookingId);
        return generateTicketPdf(booking, booking.getEvent(), seats);
    }

    private byte[] qrCodePng(String content) throws WriterException, java.io.IOException {
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", png);
        return png.toByteArray();
    }

    // ============================ Gate check-in ============================

    /**
     * Verifies a scanned token, then records the check-in — <b>outside</b> any
     * transaction of its own, so the claim/replay pair in {@link TicketCheckinService}
     * runs as two genuinely separate transactions (see that class's comment).
     * A signature failure or stale/invalid booking-seat state throws; an
     * already-checked-in seat returns successfully with {@code alreadyCheckedIn=true}
     * so re-scans aren't treated as fraud.
     */
    public TicketVerifyResponse verifyAndCheckIn(String token, String scannerEmail) {
        TicketPayload payload = verifyToken(token);
        try {
            TicketVerifyResponse response = ticketCheckinService.attemptCheckIn(payload, scannerEmail);
            log.info("Checked in booking={} seat={} by {}", payload.bookingId(), payload.seatId(), scannerEmail);
            return response;
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Duplicate check-in for seat={}; replaying existing record", payload.seatId());
            return ticketCheckinService.replayCheckIn(payload);
        }
    }
}
