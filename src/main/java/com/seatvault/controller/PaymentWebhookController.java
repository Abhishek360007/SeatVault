package com.seatvault.controller;

import com.seatvault.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint for receiving asynchronous webhook events from payment providers.
 */
@RestController
@RequestMapping("/api/v1/payments/webhook")
@Tag(name = "Payment Webhooks", description = "Asynchronous payment status updates")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);
    private final BookingService bookingService;

    public PaymentWebhookController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Operation(summary = "Process Stripe payment webhooks asynchronously")
    @PostMapping("/stripe")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {
        
        // TODO(decision): Select production payment provider (e.g. Stripe or Razorpay) and configure webhook endpoints.
        // For production, authenticate payload signature:
        // try {
        //     Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        //     if ("payment_intent.succeeded".equals(event.getType())) {
        //         PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer().getObject().orElse(null);
        //         if (intent != null) {
        //             Long bookingId = Long.parseLong(intent.getMetadata().get("bookingId"));
        //             bookingService.finalizeAsyncPayment(bookingId, intent.getId(), "APPROVED");
        //         }
        //     }
        // } catch (SignatureVerificationException e) {
        //     return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        // }

        log.info("Received Stripe webhook payload. Signature present: {}", sigHeader != null);
        if (sigHeader == null || sigHeader.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing signature");
        }
        
        return ResponseEntity.ok("Received");
    }
}
