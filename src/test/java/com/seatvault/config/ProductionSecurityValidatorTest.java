package com.seatvault.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityValidatorTest {

    private static final JwtProperties GOOD_JWT =
            new JwtProperties("a-unique-random-production-signing-secret-0123456789", 15, 7);
    private static final TicketProperties GOOD_TICKET =
            new TicketProperties("a-unique-random-production-ticket-hmac-secret-0123456789");
    private static final PaymentProperties SIMULATED = new PaymentProperties("simulated", null, "s", null, 0);

    @Test
    void rejectsDefaultJwtSecretContainingChangeMe() {
        JwtProperties badJwt = new JwtProperties("dev-only-change-me-please", 15, 7);
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(badJwt, GOOD_TICKET, "a-strong-unique-password", SIMULATED);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsNullJwtSecret() {
        JwtProperties nullJwt = new JwtProperties(null, 15, 7);
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(nullJwt, GOOD_TICKET, "a-strong-unique-password", SIMULATED);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsDefaultTicketSecretContainingChangeMe() {
        TicketProperties badTicket = new TicketProperties("dev-only-change-me-please");
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(GOOD_JWT, badTicket, "a-strong-unique-password", SIMULATED);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TICKET_HMAC_SECRET");
    }

    @Test
    void rejectsDefaultAdminPassword() {
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(GOOD_JWT, GOOD_TICKET, "admin12345", SIMULATED);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    @Test
    void allowsSecureConfiguration() {
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(GOOD_JWT, GOOD_TICKET, "a-strong-unique-password", SIMULATED);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsRazorpayWithoutWebhookSecret() {
        PaymentProperties razorpay = new PaymentProperties("razorpay", null, null,
                new PaymentProperties.Razorpay("rzp_live_x", "secret", " ", null), 0);
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(GOOD_JWT, GOOD_TICKET, "a-strong-unique-password", razorpay);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAZORPAY_WEBHOOK_SECRET");
    }

    @Test
    void rejectsBlankOrShortAdminPassword() {
        for (String weak : new String[]{"", "   ", "short-pass"}) {
            ProductionSecurityValidator validator =
                    new ProductionSecurityValidator(GOOD_JWT, GOOD_TICKET, weak, SIMULATED);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ADMIN_PASSWORD");
        }
    }
}
