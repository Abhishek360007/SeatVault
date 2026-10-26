package com.seatvault.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration payload. Bean-validation runs before the controller body executes
 * (via {@code @Valid}), so malformed input never reaches the service layer.
 */
public record RegisterRequest(

        @NotBlank(message = "email is required")
        @Email(message = "must be a valid email address")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must be 8-72 characters")
        // 72 is BCrypt's max significant byte length.
        String password,

        @NotBlank(message = "fullName is required")
        @Size(max = 255)
        String fullName
) {
}
