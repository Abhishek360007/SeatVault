package com.seatvault.exception;

import org.springframework.http.HttpStatus;

/**
 * 403 — the caller is authenticated but not currently allowed to perform this
 * action (distinct from {@link ResourceNotFoundException}'s 404, which is used
 * when we deliberately hide whether a resource exists). Used for the waiting-room
 * admission gate: an authenticated user without a valid admission token is a
 * legitimate, identified caller who simply hasn't been let in yet.
 */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
