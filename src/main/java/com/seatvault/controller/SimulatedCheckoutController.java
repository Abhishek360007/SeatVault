package com.seatvault.controller;

import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.payment.SimulatedPaymentGateway;
import com.seatvault.repository.PaymentRepository;
import com.seatvault.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stands in for the provider's checkout widget while the simulated provider is
 * active: the customer picks an outcome, and gets back what Razorpay's widget
 * would hand the browser (a payment id and signature, or an error). The browser
 * then goes through the real {@code /payments/verify} path.
 *
 * <p>Only exists when {@code app.payment.provider=simulated}; with Razorpay this
 * endpoint is not registered at all.
 */
@RestController
@RequestMapping("/api/v1/payments/simulated")
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "simulated", matchIfMissing = true)
@Tag(name = "Simulated payments", description = "Test-mode stand-in for the provider's checkout widget")
@SecurityRequirement(name = "bearerAuth")
public class SimulatedCheckoutController {

    public record SimulatedPayRequest(
            @NotBlank @Size(max = 64) String orderId,
            @NotNull SimulatedPaymentGateway.Outcome outcome,
            @Size(max = 20) String method) {
    }

    private final SimulatedPaymentGateway gateway;
    private final PaymentRepository paymentRepository;

    public SimulatedCheckoutController(SimulatedPaymentGateway gateway, PaymentRepository paymentRepository) {
        this.gateway = gateway;
        this.paymentRepository = paymentRepository;
    }

    @Operation(summary = "Complete a simulated checkout with a chosen outcome (test mode only)")
    @PostMapping("/checkout")
    public ResponseEntity<SimulatedPaymentGateway.SimulatedCheckoutResult> checkout(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody SimulatedPayRequest request) {
        // Only the order's owner may "pay" it, as only they could open its widget.
        paymentRepository.findByProviderOrderId(request.orderId())
                .filter(p -> p.getUserId().equals(principal.getUser().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        try {
            return ResponseEntity.ok(gateway.simulateCheckout(request.orderId(), request.outcome(), request.method()));
        } catch (IllegalArgumentException unknown) {
            // Simulated orders live in memory; after a restart they're gone.
            throw new ResourceNotFoundException("This test order has expired; please start checkout again");
        }
    }
}
