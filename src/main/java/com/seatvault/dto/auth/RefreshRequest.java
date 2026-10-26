package com.seatvault.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for rotating access/refresh tokens.
 */
public record RefreshRequest(
        @NotBlank(message = "refreshToken is required")
        String refreshToken
) {
}
