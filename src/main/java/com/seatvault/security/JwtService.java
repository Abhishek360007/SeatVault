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
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates stateless JWTs (HS256).
 *
 * <p><b>Why stateless JWT?</b> No server-side session store, so the API scales
 * horizontally without sticky sessions or shared session storage — important
 * because the booking system is designed to run as multiple instances (Phase 5
 * Redis lock). The usual trade-off is that a JWT stays valid until it expires. We
 * mitigate that with a short TTL ({@code app.jwt.expiration-minutes}, default 15)
 * and, for logout, a Redis blocklist keyed by the token's unique id ({@code jti});
 * see {@link TokenBlocklistService}.
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
                .id(UUID.randomUUID().toString()) // jti: lets logout revoke exactly this token
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
     * Returns the claims if the token is well-formed, correctly signed and unexpired;
     * empty otherwise. Lets callers validate and read claims with a single parse.
     */
    public Optional<Claims> parseValid(String token) {
        try {
            return Optional.of(parse(token));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Returns true if the token is well-formed, correctly signed, and unexpired.
     * Any {@link JwtException} (bad signature, malformed, expired) means invalid.
     */
    public boolean isValid(String token) {
        return parseValid(token).isPresent();
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
