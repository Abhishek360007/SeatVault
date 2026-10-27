package com.seatvault.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Centralized exception handling. Every error leaves the API as an {@link ApiError}
 * with a consistent shape, and we log at the level appropriate to severity
 * (client mistakes at WARN/DEBUG, our bugs at ERROR).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Rate-limit rejection → 429 with a {@code Retry-After} header. Declared before
     * the generic {@link ApiException} handler so we can attach the header.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleRateLimited(TooManyRequestsException ex, HttpServletRequest req) {
        log.debug("Rate limited on {}: {}", req.getRequestURI(), ex.getMessage());
        ApiError body = toBody(ex.getStatus(), ex.getMessage(), req, List.of());
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    /** Our domain exceptions already carry the right status. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
        log.warn("API exception [{}]: {}", ex.getStatus(), ex.getMessage());
        return build(ex.getStatus(), ex.getMessage(), req, List.of());
    }

    /** Bean-validation failures on {@code @Valid} request bodies → 400 with field detail. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiError.FieldValidationError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        log.debug("Validation failed on {}: {}", req.getRequestURI(), fieldErrors);
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, fieldErrors);
    }

    /** Malformed/unparseable request body (e.g. broken JSON) → 400, not 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.debug("Unreadable request body on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", req, List.of());
    }

    /**
     * Query/path parameter that can't be converted (e.g. {@code ?category=FOO} or
     * {@code ?fromDate=yesterday}) → 400 naming the parameter, not 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        log.debug("Bad value for parameter '{}' on {}: {}", ex.getName(), req.getRequestURI(), ex.getValue());
        String message = "Invalid value for parameter '" + ex.getName() + "'";
        Class<?> type = ex.getRequiredType();
        if (type != null && type.isEnum()) {
            message += "; expected one of " + Arrays.toString(type.getEnumConstants());
        }
        return build(HttpStatus.BAD_REQUEST, message, req, List.of());
    }

    /** Wrong email/password at login. Deliberately vague to avoid user enumeration. */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex, HttpServletRequest req) {
        log.debug("Bad credentials on {}", req.getRequestURI());
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", req, List.of());
    }

    /** Authenticated but not allowed (e.g. USER hitting an admin endpoint) → 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        log.debug("Access denied on {}", req.getRequestURI());
        return build(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", req, List.of());
    }

    /**
     * Unknown route → 404. Without this explicit handler the catch-all below would
     * turn Spring's own {@code NoResourceFoundException} into a misleading 500.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex, HttpServletRequest req) {
        log.debug("No handler for {}", req.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "Resource not found", req, List.of());
    }

    /** Wrong HTTP method for an existing route → 405. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                           HttpServletRequest req) {
        log.debug("Method {} not allowed on {}", req.getMethod(), req.getRequestURI());
        return build(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint", req, List.of());
    }

    /** Last resort — never leak internals; log the full stack at ERROR. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error on {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req, List.of());
    }

    private ApiError.FieldValidationError toFieldError(FieldError fe) {
        return new ApiError.FieldValidationError(fe.getField(), fe.getDefaultMessage());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req,
                                           List<ApiError.FieldValidationError> fieldErrors) {
        return ResponseEntity.status(status).body(toBody(status, message, req, fieldErrors));
    }

    private ApiError toBody(HttpStatus status, String message, HttpServletRequest req,
                            List<ApiError.FieldValidationError> fieldErrors) {
        return new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                req.getRequestURI(),
                fieldErrors
        );
    }
}
