package com.seatvault.dto.auth;

/**
 * Returned by both register and login. We echo back identity + role (handy for
 * clients to render UI) alongside the bearer token and its lifetime in seconds.
 */
public record AuthResponse(
        String token,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        Long userId,
        String email,
        String fullName,
        String role
) {
    public static AuthResponse bearer(String token, String refreshToken, long expiresInSeconds,
                                      Long userId, String email, String fullName, String role) {
        return new AuthResponse(token, refreshToken, "Bearer", expiresInSeconds, userId, email, fullName, role);
    }
}
