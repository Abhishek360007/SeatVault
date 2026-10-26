package com.seatvault.exception;

import org.springframework.http.HttpStatus;

/** 400 — the request is semantically invalid (beyond bean-validation). */
public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
