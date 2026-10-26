package com.seatvault.exception;

import org.springframework.http.HttpStatus;

/**
 * 402 — the simulated payment was declined. The booking stays PENDING so the user
 * can retry within the hold window; if they never succeed, the scheduled job
 * (Phase 6) releases the seats. We never leave seats stuck because of a failed pay.
 */
public class PaymentFailedException extends ApiException {
    public PaymentFailedException(String message) {
        super(HttpStatus.PAYMENT_REQUIRED, message);
    }
}
