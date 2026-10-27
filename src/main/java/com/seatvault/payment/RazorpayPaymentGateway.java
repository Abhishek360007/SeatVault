package com.seatvault.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatvault.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Razorpay (test or live mode, decided by the keys) over its REST API.
 *
 * <ul>
 *   <li>Orders: {@code POST /v1/orders} with the booking's frozen amount; the booking
 *       reference is the order's {@code receipt}.</li>
 *   <li>Checkout signature: HMAC-SHA256 of {@code order_id|payment_id} with the key
 *       secret. Webhook signature: HMAC-SHA256 of the raw body with the webhook secret
 *       ({@code X-Razorpay-Signature}).</li>
 *   <li>Capture: payments are fetched from Razorpay (never trusted from the browser)
 *       and captured if the account is set to authorize-only.</li>
 * </ul>
 *
 * <p>Enabled with {@code app.payment.provider=razorpay}; it refuses to start without
 * its key id and secret.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "razorpay")
public class RazorpayPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);

    private final PaymentProperties.Razorpay config;
    private final RestClient http;

    public RazorpayPaymentGateway(PaymentProperties properties, RestClient.Builder builder) {
        this.config = properties.razorpay();
        if (!StringUtils.hasText(config.keyId()) || !StringUtils.hasText(config.keySecret())) {
            throw new IllegalStateException("app.payment.provider=razorpay needs RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET");
        }
        if (!StringUtils.hasText(config.webhookSecret())) {
            log.warn("RAZORPAY_WEBHOOK_SECRET is not set: webhooks will be rejected, so payments are confirmed "
                    + "only from the browser callback and the reconciliation sweep");
        }
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(15));
        String basic = Base64.getEncoder().encodeToString(
                (config.keyId() + ":" + config.keySecret()).getBytes(StandardCharsets.UTF_8));
        this.http = builder.clone()
                .baseUrl(config.baseUrl())
                .requestFactory(timeouts)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .build();
    }

    @Override
    public String providerName() {
        return "razorpay";
    }

    @Override
    public String publicKeyId() {
        return config.keyId();
    }

    @Override
    public ProviderOrder createOrder(OrderCommand command) {
        JsonNode order = post("/v1/orders", Map.of(
                "amount", command.amountMinor(),
                "currency", command.currency(),
                "receipt", command.bookingReference(),
                "notes", Map.of("bookingId", String.valueOf(command.bookingId()),
                        "bookingReference", command.bookingReference())));
        return new ProviderOrder(order.path("id").asText(), order.path("amount").asLong(), order.path("currency").asText());
    }

    @Override
    public boolean verifyCheckoutSignature(String orderId, String paymentId, String signature) {
        return HmacSha256.matches(config.keySecret(), orderId + "|" + paymentId, signature);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return HmacSha256.matches(config.webhookSecret(), rawBody, signature);
    }

    @Override
    public CaptureResult capture(String orderId, String paymentId, long amountMinor, String currency) {
        JsonNode payment = get("/v1/payments/" + paymentId);
        if (!orderId.equals(payment.path("order_id").asText())) {
            return CaptureResult.notCaptured(paymentId, "Payment does not belong to this order");
        }
        if (payment.path("amount").asLong() != amountMinor || !currency.equals(payment.path("currency").asText())) {
            return CaptureResult.notCaptured(paymentId, "Amount does not match the order");
        }
        String status = payment.path("status").asText();
        if ("authorized".equals(status)) {
            payment = post("/v1/payments/" + paymentId + "/capture", Map.of("amount", amountMinor, "currency", currency));
            status = payment.path("status").asText();
        }
        if ("captured".equals(status)) {
            return CaptureResult.ok(paymentId);
        }
        String reason = payment.path("error_description").asText("");
        return CaptureResult.notCaptured(paymentId, reason.isBlank() ? "Payment is " + status : reason);
    }

    @Override
    public Optional<String> findCapturedPayment(String orderId) {
        for (JsonNode p : get("/v1/orders/" + orderId + "/payments").path("items")) {
            if ("captured".equals(p.path("status").asText())) {
                return Optional.of(p.path("id").asText());
            }
        }
        return Optional.empty();
    }

    @Override
    public String refund(String paymentId, long amountMinor, String reason) {
        JsonNode refund = post("/v1/payments/" + paymentId + "/refund",
                Map.of("amount", amountMinor, "notes", Map.of("reason", reason)));
        return refund.path("id").asText();
    }

    /** Razorpay takes card details only in its own widget, never through our server. */
    @Override
    public PaymentResult charge(PaymentCommand command) {
        throw new UnsupportedOperationException(
                "Direct charges aren't possible with Razorpay; use POST /bookings/{id}/checkout");
    }

    private JsonNode get(String path) {
        return call(() -> http.get().uri(path).retrieve().body(JsonNode.class), path);
    }

    private JsonNode post(String path, Object body) {
        return call(() -> http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class), path);
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> request, String path) {
        try {
            JsonNode body = request.get();
            if (body == null) {
                throw new RazorpayException("Empty response from Razorpay for " + path);
            }
            return body;
        } catch (RestClientResponseException e) {
            // Razorpay explains failures in {"error":{"description":...}}; keep that, never the auth header.
            throw new RazorpayException("Razorpay " + path + " failed with HTTP " + e.getStatusCode().value()
                    + ": " + e.getResponseBodyAsString());
        }
    }

    /** A Razorpay API call failed; callers map this to "payment provider error". */
    public static class RazorpayException extends RuntimeException {
        public RazorpayException(String message) {
            super(message);
        }
    }
}
