package com.ridesharing.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard wrapper for ALL successful API responses.
 * Every controller returns this so the frontend always gets a consistent shape:
 * {
 *   "success": true,
 *   "message": "Ride created successfully",
 *   "data": { ... actual data ... }
 * }
 *
 * The <T> is a generic type — it means "data" can hold ANY type
 * (a User, a Ride, a List of Drivers, etc.)
 *
 * @param <T> the type of data this response carries
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;

    /**
     * Creates a successful response with data.
     *
     * @param message human-readable success message
     * @param data the payload to send to the client
     * @return ApiResponse with success=true
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    /**
     * Creates a successful response with no data (e.g., delete operations).
     *
     * @param message human-readable success message
     * @return ApiResponse with success=true and data=null
     */
    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .build();
    }

    /**
     * Creates a failure response (used by GlobalExceptionHandler).
     *
     * @param message human-readable error message
     * @return ApiResponse with success=false
     */
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }
}
