package com.seatvault.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic, simulated payment provider.
 *
 * <p>Outcome is driven by {@code paymentMethod} so flows are testable without
 * randomness:
 * <ul>
 *   <li>{@code "FAIL_CARD"} → declined.</li>
 *   <li>{@code "TIMEOUT_CARD"} → simulates a slow/timed-out provider (sleep) then
 *       declines, to exercise the "don't leave seats stuck" path.</li>
 *   <li>anything else → approved.</li>
 * </ul>
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);
    private static final long SIMULATED_TIMEOUT_MS = 1_500;

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

    private void sleepQuietly() {
        try {
            Thread.sleep(SIMULATED_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
