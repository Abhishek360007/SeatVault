package com.seatvault.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityValidatorTest {

    private static final JwtProperties GOOD_JWT =
            new JwtProperties("a-unique-random-production-signing-secret-0123456789", 15, 7);
    private static final TicketProperties GOOD_TICKET =
            new TicketProperties("a-unique-random-production-ticket-hmac-secret-0123456789");

    @Test
    void rejectsDefaultJwtSecretContainingChangeMe() {
        JwtProperties badJwt = new JwtProperties("dev-only-change-me-please", 15, 7);
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(badJwt, GOOD_TICKET, "a-strong-unique-password");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsNullJwtSecret() {
        JwtProperties nullJwt = new JwtProperties(null, 15, 7);
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(nullJwt, GOOD_TICKET, "a-strong-unique-password");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsDefaultTicketSecretContainingChangeMe() {
        TicketProperties badTicket = new TicketProperties("dev-only-change-me-please");
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(GOOD_JWT, badTicket, "a-strong-unique-password");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TICKET_HMAC_SECRET");
    }

    @Test
    void rejectsDefaultAdminPassword() {
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(GOOD_JWT, GOOD_TICKET, "admin12345");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    @Test
    void allowsSecureConfiguration() {
        ProductionSecurityValidator validator =
                new ProductionSecurityValidator(GOOD_JWT, GOOD_TICKET, "a-strong-unique-password");

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }
}
