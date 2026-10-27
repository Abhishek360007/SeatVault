package com.seatvault.controller;

import com.seatvault.service.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives payment webhooks from the provider. Public (the provider can't log in),
 * so every request must carry a valid HMAC-SHA256 signature over the exact raw
 * body; anything else is rejected with 400. The body is taken as a String, not
 * parsed JSON, because re-serializing would change the bytes that were signed.
 *
 * <p>Point the Razorpay dashboard webhook (events: {@code payment.captured},
 * {@code payment.failed}, {@code order.paid}) at
 * {@code https://<host>/api/v1/payments/webhook/razorpay}.
 */
@RestController
@RequestMapping("/api/v1/payments/webhook")
@Tag(name = "Payment Webhooks", description = "Signed payment status updates from the provider")
public class PaymentWebhookController {

    private final CheckoutService checkoutService;

    public PaymentWebhookController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Operation(summary = "Razorpay webhook (signature-verified, idempotent)")
    @PostMapping("/razorpay")
    public ResponseEntity<Void> razorpay(@RequestBody String rawBody,
                                         @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        checkoutService.handleWebhook(rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
