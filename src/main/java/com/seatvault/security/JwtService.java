package com.seatvault.security;

import com.seatvault.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Issues and validates stateless JWTs (HS256).
 *
 * <p><b>Why stateless JWT?</b> No server-side session store, so the API scales
 * horizontally without sticky sessions or shared session storage — important
 * because the booking system is designed to run as multiple instances (Phase 5
 * Redis lock). The trade-off is that tokens can't be revoked before expiry; we
 * mitigate with a short TTL (configurable, default 60 min).
 *
 * <p>The subject is the user's email; the role is carried as a custom claim so the
 * filter can build authorities without a DB hit (though we still load the user to
 * ensure the account still exists).
 */
@Service
public class JwtService {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(JwtProperties properties) {
        // HS256 requires a >=256-bit key; the configured secret must be 32+ chars.
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = properties.expirationMinutes();
    }

    public String generateToken(String email, String role) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expirationMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(email)
                .claim(ROLE_CLAIM, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)  // algorithm inferred from key size (HS256)
                .compact();
    }

    public String extractEmail(String token) {
        return parse(token).getSubject();
    }

    public String extractRole(String token) {
        return parse(token).get(ROLE_CLAIM, String.class);
    }

    /**
     * Returns true if the token is well-formed, correctly signed, and unexpired.
     * Any {@link JwtException} (bad signature, malformed, expired) means invalid.
     */
    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
