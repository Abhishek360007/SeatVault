package com.seatvault.exception;

import org.springframework.http.HttpStatus;

/** 404 — a referenced entity does not exist. */
public class ResourceNotFoundException extends ApiException {
    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
