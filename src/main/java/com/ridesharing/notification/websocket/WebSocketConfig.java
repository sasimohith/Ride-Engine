package com.ridesharing.notification.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures WebSocket with STOMP protocol for real-time notifications.
 *
 * WHAT: WebSocket = persistent bidirectional connection (not request-response like HTTP).
 *       STOMP = sub-protocol that adds topics/subscriptions on top of WebSocket.
 *
 * WHY:  When a driver accepts a ride, the rider needs to know INSTANTLY.
 *       With HTTP, the rider would need to poll every second ("is my driver here?").
 *       With WebSocket, the server PUSHES the update to the rider's connection.
 *
 * HOW:
 *   1. Client connects to ws://localhost:8080/ws (the STOMP endpoint)
 *   2. Client subscribes to a topic: /topic/rider/{riderId}
 *   3. Server sends a message to that topic using SimpMessagingTemplate
 *   4. Client receives the message instantly
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Configures the message broker.
     *
     * /topic   — for broadcasting (e.g., /topic/rider/123 = all messages for rider 123)
     * /app     — prefix for messages SENT BY the client to the server
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Registers the STOMP endpoint that clients connect to.
     *
     * ws://localhost:8080/ws — the WebSocket handshake URL.
     * withSockJS() — fallback for browsers that don't support WebSocket natively.
     * setAllowedOriginPatterns("*") — allows connections from any origin (restrict in prod).
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
