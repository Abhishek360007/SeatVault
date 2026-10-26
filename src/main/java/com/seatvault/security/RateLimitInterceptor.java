package com.seatvault.security;

import com.seatvault.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies per-user rate limiting to the booking hold endpoint. Registered for
 * {@code POST /api/v1/bookings/hold} only (see {@code WebConfig}). Runs after the
 * JWT filter has populated the security context, so the authenticated user is known.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String BUCKET = "booking-hold";

    private final RateLimiterService rateLimiterService;

    public RateLimitInterceptor(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            // Throwing here lets the existing @RestControllerAdvice produce the 429 body.
            rateLimiterService.checkLimit(BUCKET, principal.getId());
        }
        // If somehow unauthenticated, let security handle it (401) downstream.
        return true;
    }
}
