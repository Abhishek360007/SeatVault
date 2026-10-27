package com.seatvault.payment;

import java.util.Optional;

/**
 * Abstraction over an external payment provider. Kept behind an interface so the
 * booking service depends on a port, not a concrete provider — and so tests can
 * swap in a deterministic fake.
 *
 * <h2>Checkout flow (order-based, as Razorpay works)</h2>
 * <ol>
 *   <li>{@link #createOrder} — the server opens an order for the booking's frozen
 *       amount. The amount lives on the provider side from here on, so the browser
 *       can't change what gets charged.</li>
 *   <li>The browser pays that order inside the provider's own widget; card/UPI
 *       details never touch SeatVault (keeps us out of PCI scope).</li>
 *   <li>The widget hands back {@code (orderId, paymentId, signature)}; the server
 *       checks it with {@link #verifyCheckoutSignature} and asks the provider for the
 *       payment's real state with {@link #capture}. The provider's webhook
 *       ({@link #verifyWebhookSignature}) delivers the same news independently.</li>
 * </ol>
 * Every call here may hit the network, so callers make them OUTSIDE database
 * transactions and never while holding row locks.
 */
public interface PaymentGateway {

    /** Short provider id stored on payments, e.g. {@code razorpay} or {@code simulated}. */
    String providerName();

    /** The key the browser widget is opened with. Public by design (never the secret). */
    String publicKeyId();

    /** Opens a provider order for {@code amountMinor}. */
    ProviderOrder createOrder(OrderCommand command);

    /** True if {@code signature} is the provider's signature over this order + payment pair. */
    boolean verifyCheckoutSignature(String orderId, String paymentId, String signature);

    /** True if {@code signature} is the provider's signature over the raw webhook body. */
    boolean verifyWebhookSignature(String rawBody, String signature);

    /**
     * Makes sure a payment on {@code orderId} is captured (captures it if the account
     * only authorizes), and returns its outcome. Never trusts the browser: this is
     * the provider's own answer.
     */
    CaptureResult capture(String orderId, String paymentId, long amountMinor, String currency);

    /** A captured payment on this order, if the provider has one (for reconciliation). */
    Optional<String> findCapturedPayment(String orderId);

    /** Returns a captured payment in full. Returns the provider's refund id. */
    String refund(String paymentId, long amountMinor, String reason);

    /**
     * Direct server-side charge, used by the original {@code POST /bookings/{id}/pay}
     * endpoint. Only possible with the simulated provider: real providers take card
     * details in their own widget, never via our server.
     */
    PaymentResult charge(PaymentCommand command);

    record OrderCommand(Long bookingId, String bookingReference, long amountMinor, String currency) {
    }

    record ProviderOrder(String orderId, long amountMinor, String currency) {
    }

    /** {@code captured=false} carries the provider's reason (declined, wrong order...). */
    record CaptureResult(boolean captured, String paymentId, String message) {
        public static CaptureResult ok(String paymentId) {
            return new CaptureResult(true, paymentId, "captured");
        }

        public static CaptureResult notCaptured(String paymentId, String message) {
            return new CaptureResult(false, paymentId, message);
        }
    }

    record PaymentCommand(Long bookingId, Long userId, String paymentMethod, long amountMinor, String currency) {
    }

    record PaymentResult(boolean success, String reference, String message) {
        public static PaymentResult ok(String reference) {
            return new PaymentResult(true, reference, "approved");
        }

        public static PaymentResult declined(String message) {
            return new PaymentResult(false, null, message);
        }
    }
}
