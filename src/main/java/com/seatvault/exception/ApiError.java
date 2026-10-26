package com.seatvault.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Consistent error response body returned for every failure across the API.
 * Keeping one shape means clients can always parse errors the same way.
 *
 * @param timestamp when the error occurred (UTC)
 * @param status    HTTP status code
 * @param error     HTTP reason phrase
 * @param message   human-readable summary
 * @param path      request URI that failed
 * @param fieldErrors per-field validation messages (omitted when empty)
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldValidationError> fieldErrors
) {
    public record FieldValidationError(String field, String message) {
    }
}
