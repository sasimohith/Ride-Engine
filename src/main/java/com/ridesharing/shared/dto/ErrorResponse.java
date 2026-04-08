package com.ridesharing.shared.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Detailed error response returned when something goes wrong.
 * Used by GlobalExceptionHandler to give the client useful error info:
 * {
 *   "status": 404,
 *   "message": "Rider with id 123 not found",
 *   "timestamp": "2026-03-29T01:45:00",
 *   "path": "/api/riders/123"
 * }
 *
 * The frontend can use "status" for logic and "message" for display.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private int status;
    private String message;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    private String path;
}
