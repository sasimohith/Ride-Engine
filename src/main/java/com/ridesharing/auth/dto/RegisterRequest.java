package com.ridesharing.auth.dto;

import com.ridesharing.shared.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the client sends to POST /api/auth/register.
 *
 * Validation annotations prevent bad data from reaching the service layer:
 *   @NotBlank  → field cannot be null or empty string
 *   @Email     → must be a valid email format
 *   @Size      → password must be 6-100 characters
 *   @NotNull   → role must be provided
 *
 * If validation fails, Spring returns a 400 Bad Request automatically
 * (caught by our GlobalExceptionHandler → handleValidationErrors).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid format")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 6, max = 100, message = "Password must be between 6 and 100 characters")
    private String password;

    @NotBlank(message = "Phone number is required")
    private String phone;

    @NotNull(message = "Role is required (RIDER or DRIVER)")
    private UserRole role;
}
