package com.seatvault.payment;

import com.seatvault.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic, simulated payment provider — the default, so the whole booking
 * flow runs locally with no provider account.
 *
 * <p>It imitates Razorpay closely enough that the real verification code runs
 * against it: orders get ids, the browser's "widget" (SeatVault's test-payment
 * panel, via {@link #simulateCheckout}) gets back a payment id plus an HMAC-SHA256
 * signature over {@code orderId|paymentId}, and webhooks are signed over the raw
 * body. Only the signing secret differs ({@code app.payment.simulated-secret}).
 *
 * <p>State is in memory: fine for a single local instance, and lost on restart, in
 * which case open simulated orders simply can't be paid any more.
 *
 * <p>The direct {@link #charge} path keeps its original behaviour, driven by
 * {@code paymentMethod}: {@code FAIL_CARD} declines, {@code TIMEOUT_CARD} sleeps then
 * declines, anything else approves.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private static final long SIMULATED_TIMEOUT_MS = 1_500;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the simulated customer did in the test-payment panel. */
    public enum Outcome { SUCCESS, FAILURE }

    /** Mirrors the fields Razorpay's checkout hands to its success / failure handlers. */
    public record SimulatedCheckoutResult(String orderId, String paymentId, String signature, String errorDescription) {
    }

    private record SimOrder(long amountMinor, String currency) {
    }

    private record SimPayment(String orderId, boolean captured, String failure) {
    }

    private final String secret;
    private final Map<String, SimOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, SimPayment> payments = new ConcurrentHashMap<>();

    public SimulatedPaymentGateway(PaymentProperties properties) {
        this.secret = properties.simulatedSecret();
    }

    @Override
    public String providerName() {
        return "simulated";
    }

    @Override
    public String publicKeyId() {
        return "sim_test_key";
    }

    @Override
    public ProviderOrder createOrder(OrderCommand command) {
        String orderId = "order_sim_" + randomId();
        orders.put(orderId, new SimOrder(command.amountMinor(), command.currency()));
        log.info("Simulated order {} for booking={} amountMinor={}", orderId, command.bookingId(), command.amountMinor());
        return new ProviderOrder(orderId, command.amountMinor(), command.currency());
    }

    /**
     * The simulated customer finishing the widget. SUCCESS captures and returns a
     * signed result; FAILURE returns an error description, like a declined card.
     */
    public SimulatedCheckoutResult simulateCheckout(String orderId, Outcome outcome, String method) {
        if (!orders.containsKey(orderId)) {
            throw new IllegalArgumentException("Unknown simulated order " + orderId);
        }
        String paymentId = "pay_sim_" + randomId();
        if (outcome == Outcome.FAILURE) {
            String reason = "Payment declined by bank (" + (method == null ? "card" : method.toLowerCase()) + ", simulated)";
            payments.put(paymentId, new SimPayment(orderId, false, reason));
            return new SimulatedCheckoutResult(orderId, paymentId, null, reason);
        }
        payments.put(paymentId, new SimPayment(orderId, true, null));
        return new SimulatedCheckoutResult(orderId, paymentId, signCheckout(orderId, paymentId), null);
    }

    /** {@code orderId|paymentId} signature, as Razorpay computes it with the key secret. */
    public String signCheckout(String orderId, String paymentId) {
        return HmacSha256.hex(secret, orderId + "|" + paymentId);
    }

    /** Signs a raw webhook body, as Razorpay does with the webhook secret (for tests). */
    public String signWebhook(String rawBody) {
        return HmacSha256.hex(secret, rawBody);
    }

    @Override
    public boolean verifyCheckoutSignature(String orderId, String paymentId, String signature) {
        return HmacSha256.matches(secret, orderId + "|" + paymentId, signature);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return HmacSha256.matches(secret, rawBody, signature);
    }

    @Override
    public CaptureResult capture(String orderId, String paymentId, long amountMinor, String currency) {
        SimPayment payment = payments.get(paymentId);
        SimOrder order = orders.get(orderId);
        if (payment == null || order == null || !payment.orderId().equals(orderId)) {
            return CaptureResult.notCaptured(paymentId, "Payment not found for this order");
        }
        if (order.amountMinor() != amountMinor || !order.currency().equals(currency)) {
            return CaptureResult.notCaptured(paymentId, "Amount does not match the order");
        }
        return payment.captured()
                ? CaptureResult.ok(paymentId)
                : CaptureResult.notCaptured(paymentId, payment.failure());
    }

    @Override
    public Optional<String> findCapturedPayment(String orderId) {
        return payments.entrySet().stream()
                .filter(e -> e.getValue().orderId().equals(orderId) && e.getValue().captured())
                .map(Map.Entry::getKey)
                .findFirst();
    }

    @Override
    public String refund(String paymentId, long amountMinor, String reason) {
        log.info("Simulated refund of {} (amountMinor={}): {}", paymentId, amountMinor, reason);
        return "rfnd_sim_" + randomId();
    }

    @Override
    public PaymentResult charge(PaymentCommand command) {
        String method = command.paymentMethod() == null ? "" : command.paymentMethod().toUpperCase();
        log.info("Charging bookingId={} amountMinor={} method={}",
                command.bookingId(), command.amountMinor(), method);
        switch (method) {
            case "FAIL_CARD" -> {
                return PaymentResult.declined("Card declined");
            }
            case "TIMEOUT_CARD" -> {
                sleepQuietly();
                return PaymentResult.declined("Payment provider timed out");
            }
            default -> {
                return PaymentResult.ok("PAY-" + command.bookingId() + "-" + command.amountMinor());
            }
        }
    }

    private static String randomId() {
        byte[] bytes = new byte[7];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(SIMULATED_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
