package com.seatvault.controller;

import com.seatvault.dto.ticket.CheckinSummary;
import com.seatvault.dto.ticket.TicketVerifyRequest;
import com.seatvault.dto.ticket.TicketVerifyResponse;
import com.seatvault.security.AppUserPrincipal;
import com.seatvault.service.TicketCheckinService;
import com.seatvault.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gate check-in scanning. Restricted to ADMIN/SCANNER — a normal user must never be
 * able to mark someone else's ticket as checked in.
 */
@RestController
@RequestMapping("/api/v1/tickets")
@Tag(name = "Gate Check-in", description = "Scanner verification of e-ticket QR tokens")
@SecurityRequirement(name = "bearerAuth")
public class TicketVerificationController {

    private final TicketService ticketService;
    private final TicketCheckinService ticketCheckinService;

    public TicketVerificationController(TicketService ticketService, TicketCheckinService ticketCheckinService) {
        this.ticketService = ticketService;
        this.ticketCheckinService = ticketCheckinService;
    }

    @Operation(summary = "Verify a scanned ticket QR token and record the gate check-in (ADMIN/SCANNER)")
    @PreAuthorize("hasAnyRole('ADMIN', 'SCANNER')")
    @PostMapping("/verify")
    public ResponseEntity<TicketVerifyResponse> verify(@AuthenticationPrincipal AppUserPrincipal principal,
                                                        @Valid @RequestBody TicketVerifyRequest request) {
        return ResponseEntity.ok(ticketService.verifyAndCheckIn(request.token(), request.eventId(),
                principal.getUsername()));
    }

    @Operation(summary = "Tickets sold vs. admitted for an event, for the gate screen (ADMIN/SCANNER)")
    @PreAuthorize("hasAnyRole('ADMIN', 'SCANNER')")
    @GetMapping("/summary")
    public ResponseEntity<CheckinSummary> summary(@RequestParam Long eventId) {
        return ResponseEntity.ok(ticketCheckinService.summary(eventId));
    }
}
