package com.seatvault.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.seatvault.config.DisplayFormat;
import com.seatvault.config.TicketProperties;
import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.domain.Event;
import com.seatvault.domain.Role;
import com.seatvault.domain.Seat;
import com.seatvault.domain.User;
import com.seatvault.dto.ticket.TicketPass;
import com.seatvault.dto.ticket.TicketVerifyResponse;
import com.seatvault.exception.BadRequestException;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.repository.TicketCheckinRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.awt.Color;
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
    private final TicketCheckinRepository ticketCheckinRepository;
    private final DisplayFormat display;

    public TicketService(TicketProperties properties,
                         BookingRepository bookingRepository,
                         SeatRepository seatRepository,
                         TicketCheckinService ticketCheckinService,
                         TicketCheckinRepository ticketCheckinRepository,
                         DisplayFormat display) {
        this.ticketCheckinRepository = ticketCheckinRepository;
        this.display = display;
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

    private static final Color INK = new Color(0x1a, 0x1d, 0x2b);
    private static final Color MUTED = new Color(0x5a, 0x60, 0x79);
    private static final Color BAND = new Color(0x1d, 0x4e, 0xd8);
    private static final Color LINE = new Color(0xe1, 0xe4, 0xec);

    /**
     * Renders one PDF with one page per seat. Each page is a self-contained ticket:
     * event, local date/time, venue, seat, holder, booking reference and its own
     * signed QR, so seats can be printed or forwarded to different people.
     */
    public byte[] generateTicketPdf(Booking booking, Event event, List<Seat> seats) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A5, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.addTitle("SeatVault e-ticket " + booking.getReference());
            document.open();
            for (int i = 0; i < seats.size(); i++) {
                if (i > 0) {
                    document.newPage();
                }
                document.add(ticketPage(booking, event, seats.get(i), i + 1, seats.size()));
            }
            document.close();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate ticket PDF", ex);
        }
        return out.toByteArray();
    }

    private PdfPTable ticketPage(Booking booking, Event event, Seat seat, int n, int total)
            throws WriterException, java.io.IOException, com.lowagie.text.DocumentException {
        PdfPTable ticket = new PdfPTable(1);
        ticket.setWidthPercentage(100);

        PdfPCell band = new PdfPCell(new Phrase("SEATVAULT  ·  E-TICKET",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE)));
        band.setBackgroundColor(BAND);
        band.setBorder(Rectangle.NO_BORDER);
        band.setPadding(10);
        ticket.addCell(band);

        Paragraph heading = new Paragraph();
        heading.add(new Chunk(event.getTitle() + "\n", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 17, INK)));
        String place = event.getCity() == null ? event.getVenue() : event.getVenue() + ", " + event.getCity();
        heading.add(new Chunk(place, FontFactory.getFont(FontFactory.HELVETICA, 10, MUTED)));
        ticket.addCell(plain(heading, 14));

        PdfPTable facts = new PdfPTable(new float[]{1, 1, 1});
        facts.setWidthPercentage(100);
        fact(facts, "DATE", display.date(event.getEventDateTime()));
        fact(facts, "TIME", display.time(event.getEventDateTime()));
        fact(facts, "SEAT", seat.getSeatLabel());
        fact(facts, "TIER", seat.getTierName() == null ? "Standard" : seat.getTierName());
        fact(facts, "HOLDER", booking.getUser().getFullName());
        fact(facts, "REFERENCE", booking.getReference());
        PdfPCell factsCell = new PdfPCell(facts);
        factsCell.setBorder(Rectangle.TOP);
        factsCell.setBorderColor(LINE);
        factsCell.setPadding(8);
        ticket.addCell(factsCell);

        Image qr = Image.getInstance(qrCodePng(signToken(booking.getId(), seat.getId(), event.getId())));
        qr.scaleAbsolute(190, 190);
        PdfPCell qrCell = new PdfPCell(qr, false);
        qrCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        qrCell.setBorder(Rectangle.TOP);
        qrCell.setBorderColor(LINE);
        qrCell.setPadding(12);
        ticket.addCell(qrCell);

        Paragraph note = new Paragraph("Show this QR code at the entrance. It admits one person, once. "
                + "Ticket " + n + " of " + total + ".", FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED));
        note.setAlignment(Element.ALIGN_CENTER);
        PdfPCell noteCell = plain(note, 8);
        noteCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        ticket.addCell(noteCell);
        return ticket;
    }

    private static void fact(PdfPTable table, String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + "\n", FontFactory.getFont(FontFactory.HELVETICA, 7, MUTED)));
        p.add(new Chunk(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, INK)));
        table.addCell(plain(p, 6));
    }

    private static PdfPCell plain(Paragraph content, float padding) {
        PdfPCell cell = new PdfPCell();
        cell.addElement(content);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(padding);
        return cell;
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

    /**
     * The owner's on-screen tickets: one pass per seat with its signed token and QR
     * image, plus whether it has already been used. Same access rule as the PDF.
     */
    @Transactional(readOnly = true)
    public List<TicketPass> passesForBooking(User caller, Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        boolean owner = booking.getUser().getId().equals(caller.getId());
        if (!owner && caller.getRole() != Role.ADMIN) {
            throw new ResourceNotFoundException("Booking not found");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ConflictException("Tickets are only available for a confirmed booking");
        }
        List<Seat> seats = seatRepository.findByBookingId(bookingId).stream()
                .sorted(java.util.Comparator.comparing(Seat::getSeatLabel)).toList();
        java.util.Map<Long, Instant> used = new java.util.HashMap<>();
        ticketCheckinRepository.findBySeatIdIn(seats.stream().map(Seat::getId).toList())
                .forEach(c -> used.put(c.getSeatId(), c.getCheckedInAt()));
        Long eventId = booking.getEvent().getId();
        return seats.stream().map(seat -> {
            String token = signToken(bookingId, seat.getId(), eventId);
            try {
                String png = "data:image/png;base64," + Base64.getEncoder().encodeToString(qrCodePng(token));
                return new TicketPass(seat.getId(), seat.getSeatLabel(), seat.getTierName(), token, png,
                        used.get(seat.getId()));
            } catch (WriterException | java.io.IOException e) {
                throw new IllegalStateException("Failed to render ticket QR", e);
            }
        }).toList();
    }

    private byte[] qrCodePng(String content) throws WriterException, java.io.IOException {
        // Medium error correction survives a scuffed print or a cracked phone screen.
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 360, 360,
                java.util.Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", png);
        return png.toByteArray();
    }

    // ============================ Gate check-in ============================

    /**
     * Verifies a scanned token, then records the check-in — <b>outside</b> any
     * transaction of its own, so the claim/replay pair in {@link TicketCheckinService}
     * runs as two genuinely separate transactions (see that class's comment).
     * A signature failure, a ticket for another event, or stale/invalid
     * booking-seat state throws; an
     * already-checked-in seat returns successfully with {@code alreadyCheckedIn=true}
     * so re-scans aren't treated as fraud.
     */
    public TicketVerifyResponse verifyAndCheckIn(String token, Long gateEventId, String scannerEmail) {
        TicketPayload payload = verifyToken(token);
        if (!payload.eventId().equals(gateEventId)) {
            // Genuine ticket, wrong door: refuse without recording anything, so it
            // still works at its own event.
            log.info("Refused ticket for event={} at gate for event={}", payload.eventId(), gateEventId);
            throw new ConflictException("This ticket is for a different event");
        }
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
