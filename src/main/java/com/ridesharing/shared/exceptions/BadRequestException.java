package com.ridesharing.shared.exceptions;

/**
 * Thrown when the client sends invalid or incomplete data.
 * Examples: "Pickup location cannot be null", "Invalid email format".
 * Maps to HTTP 400 Bad Request.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
