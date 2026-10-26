package com.seatvault.controller;

import com.seatvault.dto.PagedResponse;
import com.seatvault.dto.booking.BookingResponse;
import com.seatvault.dto.booking.HoldRequest;
import com.seatvault.dto.booking.PaymentRequest;
import com.seatvault.security.AppUserPrincipal;
import com.seatvault.service.BookingService;
import com.seatvault.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Booking endpoints — all require an authenticated USER (or ADMIN). The caller is
 * taken from the JWT principal, never from the request body, so a user can only
 * ever act on their own bookings.
 */
@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings", description = "Hold, pay, confirm and manage seat bookings")
@SecurityRequirement(name = "bearerAuth")
public class BookingController {

    private final BookingService bookingService;
    private final TicketService ticketService;

    public BookingController(BookingService bookingService, TicketService ticketService) {
        this.bookingService = bookingService;
        this.ticketService = ticketService;
    }

    @Operation(summary = "Hold one or more seats for an event (creates a PENDING booking)")
    @PostMapping("/hold")
    public ResponseEntity<BookingResponse> hold(@AuthenticationPrincipal AppUserPrincipal principal,
                                                @Valid @RequestBody HoldRequest request) {
        BookingResponse response = bookingService.hold(principal.getUser(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Pay for a pending booking (simulated, idempotent). On success seats become BOOKED. "
            + "Repeated calls with the same idempotencyKey return the first result instead of charging again.")
    @PostMapping("/{id}/pay")
    public ResponseEntity<BookingResponse> pay(@AuthenticationPrincipal AppUserPrincipal principal,
                                               @PathVariable Long id,
                                               @Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.ok(bookingService.pay(principal.getUser(), id, request));
    }

    @Operation(summary = "Cancel a pending booking and release its held seats")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<BookingResponse> cancel(@AuthenticationPrincipal AppUserPrincipal principal,
                                                  @PathVariable Long id) {
        return ResponseEntity.ok(bookingService.cancel(principal.getUser(), id));
    }

    @Operation(summary = "Download the PDF e-ticket for a confirmed booking (owner or admin)")
    @GetMapping(value = "/{id}/ticket", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> ticket(@AuthenticationPrincipal AppUserPrincipal principal,
                                         @PathVariable Long id) {
        byte[] pdf = ticketService.generateForBooking(principal.getUser(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ticket-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @Operation(summary = "Get one of my bookings")
    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getById(@AuthenticationPrincipal AppUserPrincipal principal,
                                                   @PathVariable Long id) {
        return ResponseEntity.ok(bookingService.getById(principal.getUser(), id));
    }

    @Operation(summary = "List my bookings (paginated)")
    @GetMapping
    public ResponseEntity<PagedResponse<BookingResponse>> myBookings(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(bookingService.getMyBookings(principal.getUser(), pageable));
    }
}
