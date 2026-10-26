package com.seatvault.config;

import com.seatvault.security.ClientIpResolver;
import com.seatvault.security.RateLimitInterceptor;
import com.seatvault.security.IpRateLimitInterceptor;
import com.seatvault.service.RateLimiterService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers rate-limit interceptors. Keeping the path mapping here (rather than
 * annotating controllers) makes the cross-cutting concern explicit and easy to
 * extend to other buckets later.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimiterService rateLimiterService;
    private final ClientIpResolver clientIpResolver;

    public WebConfig(RateLimiterService rateLimiterService, ClientIpResolver clientIpResolver) {
        this.rateLimiterService = rateLimiterService;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(rateLimiterService))
                .addPathPatterns("/api/v1/bookings/hold");

        // Limit login attempts to 10 per minute per IP address
        registry.addInterceptor(new IpRateLimitInterceptor(rateLimiterService, clientIpResolver, "login-ip", 10, 60))
                .addPathPatterns("/api/v1/auth/login");

        // Registration is unauthenticated and cheap to spam/enumerate against.
        registry.addInterceptor(new IpRateLimitInterceptor(rateLimiterService, clientIpResolver, "register-ip", 10, 60))
                .addPathPatterns("/api/v1/auth/register");

        // Public browsing endpoints: generous limit, just enough to blunt scraping/DoS.
        registry.addInterceptor(new IpRateLimitInterceptor(rateLimiterService, clientIpResolver, "events-browse-ip", 120, 60))
                .addPathPatterns("/api/v1/events", "/api/v1/events/**");

        // Waiting-room join is unauthenticated-adjacent and cheap to hammer per event.
        registry.addInterceptor(new IpRateLimitInterceptor(rateLimiterService, clientIpResolver, "queue-join-ip", 30, 60))
                .addPathPatterns("/api/v1/events/*/queue/join");
    }
}
