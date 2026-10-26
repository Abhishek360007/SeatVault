package com.seatvault.exception;

import org.springframework.http.HttpStatus;

/**
 * 409 — one or more requested seats were not AVAILABLE (already held or booked).
 * This is the "someone beat you to it" outcome of the concurrency control, and is
 * the expected, clean failure for all-but-one of N racing requests.
 */
public class SeatUnavailableException extends ApiException {
    public SeatUnavailableException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
