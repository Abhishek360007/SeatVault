package com.seatvault.controller;

import com.seatvault.dto.ticket.TicketVerifyRequest;
import com.seatvault.dto.ticket.TicketVerifyResponse;
import com.seatvault.security.AppUserPrincipal;
import com.seatvault.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public TicketVerificationController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Operation(summary = "Verify a scanned ticket QR token and record the gate check-in (ADMIN/SCANNER)")
    @PreAuthorize("hasAnyRole('ADMIN', 'SCANNER')")
    @PostMapping("/verify")
    public ResponseEntity<TicketVerifyResponse> verify(@AuthenticationPrincipal AppUserPrincipal principal,
                                                        @Valid @RequestBody TicketVerifyRequest request) {
        return ResponseEntity.ok(ticketService.verifyAndCheckIn(request.token(), principal.getUsername()));
    }
}
