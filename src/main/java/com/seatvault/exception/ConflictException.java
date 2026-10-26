package com.seatvault.exception;

import org.springframework.http.HttpStatus;

/**
 * 409 — the request conflicts with current state. Used for duplicate
 * registration and, later, for seats that are already held/booked.
 */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
