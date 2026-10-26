package com.seatvault.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for all domain exceptions that map cleanly to an HTTP status. Carrying the
 * status on the exception lets the {@link GlobalExceptionHandler} translate without
 * a giant if/else chain.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
