package com.ridesharing.auth.dto;

import com.ridesharing.shared.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What we send back after a successful login or token refresh.
 * The client stores these tokens and sends the accessToken
 * with every future request in the Authorization header.
 *
 * Example response:
 * {
 *   "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
 *   "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
 *   "role": "RIDER"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private UserRole role;
}
