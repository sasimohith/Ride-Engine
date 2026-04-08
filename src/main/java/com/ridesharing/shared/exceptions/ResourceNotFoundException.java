package com.ridesharing.shared.exceptions;

/**
 * Thrown when a requested resource does not exist in the database.
 * Examples: "Rider with id 123 not found", "Ride with id 456 not found".
 * Maps to HTTP 404 Not Found.
 *
 * Usage: throw new ResourceNotFoundException("Rider", "id", riderId);
 * Result message: "Rider not found with id: 123"
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceName;
    private final String fieldName;
    private final Object fieldValue;

    public ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) {
        super(String.format("%s not found with %s: %s", resourceName, fieldName, fieldValue));
        this.resourceName = resourceName;
        this.fieldName = fieldName;
        this.fieldValue = fieldValue;
    }

    public String getResourceName() {
        return resourceName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public Object getFieldValue() {
        return fieldValue;
    }
}
