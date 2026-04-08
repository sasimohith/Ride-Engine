package com.ridesharing.shared.exceptions;

import com.ridesharing.shared.dto.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for GlobalExceptionHandler.
 * Verifies that each exception type returns the correct HTTP status and message.
 *
 * We test the handler directly (not through a controller) because:
 *   - Faster: no Spring context needed
 *   - Focused: we're testing the handler logic, not HTTP plumbing
 *   - Reliable: no flaky network or port issues
 *
 * What is the happy path?
 *   → Each exception maps to its correct HTTP status code
 * What are 3 ways this can fail?
 *   → Wrong status code, missing message, missing timestamp
 * What is the hardest edge case?
 *   → Generic Exception should return 500 without exposing internal details
 */
@DisplayName("GlobalExceptionHandler Tests")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");
    }

    @Test
    @DisplayName("should_return404_when_resourceNotFoundExceptionIsThrown")
    void should_return404_when_resourceNotFoundExceptionIsThrown() {
        // GIVEN
        ResourceNotFoundException ex = new ResourceNotFoundException("Rider", "id", 123L);

        // WHEN
        ResponseEntity<ErrorResponse> response = handler.handleResourceNotFound(ex, request);

        // THEN
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(404, response.getBody().getStatus());
        assertEquals("Rider not found with id: 123", response.getBody().getMessage());
        assertEquals("/api/test", response.getBody().getPath());
        assertNotNull(response.getBody().getTimestamp());
    }

    @Test
    @DisplayName("should_return400_when_badRequestExceptionIsThrown")
    void should_return400_when_badRequestExceptionIsThrown() {
        // GIVEN
        BadRequestException ex = new BadRequestException("Pickup location cannot be null");

        // WHEN
        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(ex, request);

        // THEN
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().getStatus());
        assertEquals("Pickup location cannot be null", response.getBody().getMessage());
    }

    @Test
    @DisplayName("should_return401_when_unauthorizedExceptionIsThrown")
    void should_return401_when_unauthorizedExceptionIsThrown() {
        // GIVEN
        UnauthorizedException ex = new UnauthorizedException("JWT token expired");

        // WHEN
        ResponseEntity<ErrorResponse> response = handler.handleUnauthorized(ex, request);

        // THEN
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(401, response.getBody().getStatus());
        assertEquals("JWT token expired", response.getBody().getMessage());
    }

    @Test
    @DisplayName("should_return409_when_conflictExceptionIsThrown")
    void should_return409_when_conflictExceptionIsThrown() {
        // GIVEN
        ConflictException ex = new ConflictException("Driver is already on a ride");

        // WHEN
        ResponseEntity<ErrorResponse> response = handler.handleConflict(ex, request);

        // THEN
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(409, response.getBody().getStatus());
        assertEquals("Driver is already on a ride", response.getBody().getMessage());
    }

    @Test
    @DisplayName("should_return500_when_unexpectedExceptionIsThrown")
    void should_return500_when_unexpectedExceptionIsThrown() {
        // GIVEN: a generic unexpected exception (e.g., NullPointerException)
        Exception ex = new RuntimeException("Something broke internally");

        // WHEN
        ResponseEntity<ErrorResponse> response = handler.handleGenericException(ex, request);

        // THEN: returns 500 with a GENERIC message (never expose internal error details)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().getStatus());
        assertEquals("An unexpected error occurred. Please try again later.", response.getBody().getMessage());
        assertEquals("/api/test", response.getBody().getPath());
    }
}
