package com.seatvault.exception;

import org.springframework.http.HttpStatus;

/**
 * 429 — the caller exceeded their rate limit on the booking endpoint. Carries the
 * seconds until the window resets so the handler can hint a Retry-After.
 */
public class TooManyRequestsException extends ApiException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
