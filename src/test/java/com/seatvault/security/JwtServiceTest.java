package com.seatvault.security;

import com.seatvault.config.JwtProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for JWT issuance/validation. No Spring context — we build
 * {@link JwtService} directly with a {@link JwtProperties} record.
 */
class JwtServiceTest {

    // HS256 needs a >= 256-bit (32+ char) key.
    private static final String SECRET = "unit-test-signing-secret-key-0123456789-abcdef";

    private JwtService jwtService(long expirationMinutes) {
        return new JwtService(new JwtProperties(SECRET, expirationMinutes, 7));
    }

    @Test
    void generatesTokenAndRoundTripsSubjectAndRole() {
        JwtService jwt = jwtService(60);

        String token = jwt.generateToken("alice@example.com", "USER");

        assertThat(token).isNotBlank();
        assertThat(jwt.isValid(token)).isTrue();
        assertThat(jwt.extractEmail(token)).isEqualTo("alice@example.com");
        assertThat(jwt.extractRole(token)).isEqualTo("USER");
    }

    @Test
    void rejectsMalformedToken() {
        assertThat(jwtService(60).isValid("not-a-jwt")).isFalse();
    }

    @Test
    void rejectsTokenSignedWithADifferentKey() {
        String token = jwtService(60).generateToken("a@b.com", "ADMIN");

        JwtService other = new JwtService(
                new JwtProperties("a-totally-different-but-also-long-enough-secret-key-987654", 60, 7));

        assertThat(other.isValid(token)).isFalse();
    }

    @Test
    void treatsExpiredTokenAsInvalid() {
        // Negative TTL → the token's expiry is already in the past at creation.
        JwtService jwt = jwtService(-1);
        String token = jwt.generateToken("a@b.com", "USER");

        assertThat(jwt.isValid(token)).isFalse();
    }
}
