package com.ridesharing.shared.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ApiResponse — our standard wrapper for ALL API responses.
 *
 * What is the happy path?
 *   → success() returns success=true with message and data
 * What are 3 ways this can fail?
 *   → success flag is wrong, message is wrong, data is wrong
 * What is the hardest edge case?
 *   → error() should have success=false and null data
 */
@DisplayName("ApiResponse Tests")
class ApiResponseTest {

    @Test
    @DisplayName("should_returnSuccessTrue_when_successWithDataIsCalled")
    void should_returnSuccessTrue_when_successWithDataIsCalled() {
        // GIVEN: a message and some data
        String message = "Ride created successfully";
        String data = "ride-123";

        // WHEN: we create a success response
        ApiResponse<String> response = ApiResponse.success(message, data);

        // THEN: success is true, message matches, data matches
        assertTrue(response.isSuccess());
        assertEquals("Ride created successfully", response.getMessage());
        assertEquals("ride-123", response.getData());
    }

    @Test
    @DisplayName("should_returnNullData_when_successWithoutDataIsCalled")
    void should_returnNullData_when_successWithoutDataIsCalled() {
        // GIVEN: only a message, no data (e.g., delete operation)
        String message = "Ride cancelled successfully";

        // WHEN: we create a success response without data
        ApiResponse<Void> response = ApiResponse.success(message);

        // THEN: success is true, message matches, data is null
        assertTrue(response.isSuccess());
        assertEquals("Ride cancelled successfully", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("should_returnSuccessFalse_when_errorIsCalled")
    void should_returnSuccessFalse_when_errorIsCalled() {
        // GIVEN: an error message
        String message = "Driver not found";

        // WHEN: we create an error response
        ApiResponse<Void> response = ApiResponse.error(message);

        // THEN: success is false, message matches, data is null
        assertFalse(response.isSuccess());
        assertEquals("Driver not found", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("should_holdAnyDataType_when_genericTypeIsUsed")
    void should_holdAnyDataType_when_genericTypeIsUsed() {
        // GIVEN: an Integer as data (proving <T> works with any type)
        Integer rideCount = 42;

        // WHEN: we create a success response with Integer data
        ApiResponse<Integer> response = ApiResponse.success("Total rides", rideCount);

        // THEN: data holds the Integer correctly
        assertTrue(response.isSuccess());
        assertEquals(42, response.getData());
    }
}
