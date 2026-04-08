package com.ridesharing.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The payload pushed to the client via WebSocket.
 * Contains everything the frontend needs to display a notification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage {

    private String type;
    private String title;
    private String message;
    private Long rideId;
    private Long recipientId;
    private Map<String, Object> data;
    private LocalDateTime timestamp;
}
