package com.ridesharing.shared.exceptions;

/**
 * Thrown when an action conflicts with the current state of a resource.
 * Examples: "Driver is already on a ride", "Ride has already been accepted",
 *           "Email already registered".
 * Maps to HTTP 409 Conflict.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
