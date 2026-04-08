package com.ridesharing.shared.exceptions;

/**
 * Thrown when a user tries to access a resource without valid authentication.
 * Examples: "Invalid JWT token", "Token expired", "Missing Authorization header".
 * Maps to HTTP 401 Unauthorized.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
