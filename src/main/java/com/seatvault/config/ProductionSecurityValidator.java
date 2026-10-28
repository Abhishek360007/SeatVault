package com.seatvault.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails fast on startup in the {@code prod} profile if the app is still running with
 * dev-only default secrets. These defaults exist so the jar boots without config in
 * local/dev, but shipping them to production would mean an admin account with a known
 * password and a JWT signing key an attacker can find in this repo.
 */
@Component
@Profile("prod")
public class ProductionSecurityValidator {

    private final JwtProperties jwtProperties;
    private final TicketProperties ticketProperties;
    private final String adminPassword;
    private final PaymentProperties paymentProperties;

    public ProductionSecurityValidator(JwtProperties jwtProperties,
                                        TicketProperties ticketProperties,
                                        @Value("${app.admin.password}") String adminPassword,
                                        PaymentProperties paymentProperties) {
        this.paymentProperties = paymentProperties;
        this.jwtProperties = jwtProperties;
        this.ticketProperties = ticketProperties;
        this.adminPassword = adminPassword;
    }

    @PostConstruct
    void validate() {
        if (jwtProperties.secret() == null || jwtProperties.secret().contains("change-me")) {
            throw new IllegalStateException(
                    "Refusing to start in 'prod' profile: JWT_SECRET is missing or still contains "
                            + "'change-me'. Set a unique, random secret (>= 256 bits) via the JWT_SECRET env var.");
        }
        if (ticketProperties.hmacSecret() == null || ticketProperties.hmacSecret().contains("change-me")) {
            throw new IllegalStateException(
                    "Refusing to start in 'prod' profile: TICKET_HMAC_SECRET is missing or still contains "
                            + "'change-me'. Set a unique, random secret via the TICKET_HMAC_SECRET env var.");
        }
        if (paymentProperties.isRazorpay()
                && (paymentProperties.razorpay().webhookSecret() == null
                    || paymentProperties.razorpay().webhookSecret().isBlank())) {
            throw new IllegalStateException(
                    "Refusing to start in 'prod' profile: RAZORPAY_WEBHOOK_SECRET is not set, so payments "
                            + "would be confirmed only from the browser. Set it to the webhook secret from the "
                            + "Razorpay dashboard.");
        }
        if (!paymentProperties.isRazorpay()) {
            LoggerFactory.getLogger(ProductionSecurityValidator.class).warn(
                    "'prod' profile is running with the SIMULATED payment provider: anyone can complete a "
                            + "checkout without paying. Set PAYMENT_PROVIDER=razorpay with live keys before real sales.");
        }
        if (adminPassword == null || adminPassword.isBlank() || adminPassword.length() < 12) {
            throw new IllegalStateException(
                    "Refusing to start in 'prod' profile: ADMIN_PASSWORD is missing or shorter than 12 "
                            + "characters. Set a strong password via the ADMIN_PASSWORD env var.");
        }
        if ("admin12345".equals(adminPassword)) {
            throw new IllegalStateException(
                    "Refusing to start in 'prod' profile: ADMIN_PASSWORD is left at the default "
                            + "'admin12345'. Set a strong password via the ADMIN_PASSWORD env var.");
        }
    }
}
