package com.ridesharing.shared.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for all custom exception classes.
 *
 * What is the happy path?
 *   → Each exception carries the correct message
 * What are 3 ways this can fail?
 *   → Wrong message format, missing fields, wrong exception type
 * What is the hardest edge case?
 *   → ResourceNotFoundException builds a formatted message from 3 fields
 */
@DisplayName("Custom Exception Tests")
class CustomExceptionTest {

    @Test
    @DisplayName("should_formatMessage_when_resourceNotFoundIsCreated")
    void should_formatMessage_when_resourceNotFoundIsCreated() {
        // GIVEN: resource details
        String resourceName = "Rider";
        String fieldName = "id";
        Long fieldValue = 123L;

        // WHEN: exception is created
        ResourceNotFoundException ex = new ResourceNotFoundException(resourceName, fieldName, fieldValue);

        // THEN: message is formatted as "Rider not found with id: 123"
        assertEquals("Rider not found with id: 123", ex.getMessage());
        assertEquals("Rider", ex.getResourceName());
        assertEquals("id", ex.getFieldName());
        assertEquals(123L, ex.getFieldValue());
    }

    @Test
    @DisplayName("should_carryMessage_when_badRequestIsCreated")
    void should_carryMessage_when_badRequestIsCreated() {
        // GIVEN/WHEN
        BadRequestException ex = new BadRequestException("Pickup location cannot be null");

        // THEN
        assertEquals("Pickup location cannot be null", ex.getMessage());
    }

    @Test
    @DisplayName("should_carryMessage_when_unauthorizedIsCreated")
    void should_carryMessage_when_unauthorizedIsCreated() {
        // GIVEN/WHEN
        UnauthorizedException ex = new UnauthorizedException("JWT token expired");

        // THEN
        assertEquals("JWT token expired", ex.getMessage());
    }

    @Test
    @DisplayName("should_carryMessage_when_conflictIsCreated")
    void should_carryMessage_when_conflictIsCreated() {
        // GIVEN/WHEN
        ConflictException ex = new ConflictException("Driver is already on a ride");

        // THEN
        assertEquals("Driver is already on a ride", ex.getMessage());
    }

    @Test
    @DisplayName("should_handleStringFieldValue_when_resourceNotFoundWithEmail")
    void should_handleStringFieldValue_when_resourceNotFoundWithEmail() {
        // GIVEN: searching by email instead of ID
        ResourceNotFoundException ex = new ResourceNotFoundException("User", "email", "test@mail.com");

        // THEN: message formats correctly with a String value too
        assertEquals("User not found with email: test@mail.com", ex.getMessage());
    }
}
