package com.seatvault.payment;

/**
 * Abstraction over an external payment provider. Kept behind an interface so the
 * booking service depends on a port, not a concrete provider — and so tests can
 * swap in a deterministic fake.
 */
public interface PaymentGateway {

    /**
     * Attempts to charge. Implementations must be safe to call OUTSIDE a database
     * transaction — the booking flow deliberately calls this without holding any
     * row locks (never block on the network while holding DB locks).
     */
    PaymentResult charge(PaymentCommand command);

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
