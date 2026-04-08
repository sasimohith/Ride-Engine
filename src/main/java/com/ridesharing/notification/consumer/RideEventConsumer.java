package com.ridesharing.notification.consumer;

import com.ridesharing.notification.service.NotificationService;
import com.ridesharing.ride.events.RideEvent;
import com.ridesharing.shared.kafka.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes ride lifecycle events from Kafka and triggers notifications.
 *
 * WHAT: @KafkaListener makes this method automatically called when a
 *       message arrives on the specified Kafka topic.
 *
 * WHY:  The Ride Module publishes events without knowing HOW notifications work.
 *       This consumer bridges the gap — it translates Kafka events into
 *       WebSocket notifications for real-time delivery.
 *
 * HOW:  Kafka delivers the event → this consumer receives it →
 *       calls NotificationService (which is @Async) → WebSocket push to client.
 *
 * The groupId ensures that in a multi-instance deployment,
 * only ONE instance processes each event (no duplicates).
 */
@Component
public class RideEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideEventConsumer.class);

    private final NotificationService notificationService;

    public RideEventConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = KafkaTopics.RIDE_ACCEPTED, groupId = "notification-group")
    public void onRideAccepted(RideEvent event) {
        log.info("Consumed RIDE_ACCEPTED event: rideId={}, driverId={}", event.getRideId(), event.getDriverId());

        notificationService.notifyRiderDriverAccepted(
                event.getRiderId(),
                event.getRideId(),
                event.getDriverId()
        );
    }

    @KafkaListener(topics = KafkaTopics.RIDE_COMPLETED, groupId = "notification-group")
    public void onRideCompleted(RideEvent event) {
        log.info("Consumed RIDE_COMPLETED event: rideId={}", event.getRideId());

        String fare = event.getActualFare() != null
                ? event.getActualFare().toPlainString()
                : event.getEstimatedFare().toPlainString();

        notificationService.notifyRideCompleted(
                event.getRiderId(),
                event.getDriverId(),
                event.getRideId(),
                fare
        );
    }

    @KafkaListener(topics = KafkaTopics.RIDE_CANCELLED, groupId = "notification-group")
    public void onRideCancelled(RideEvent event) {
        log.info("Consumed RIDE_CANCELLED event: rideId={}", event.getRideId());

        String reason = event.getDriverId() == null
                ? "No driver was found for your ride. Please try again."
                : "Your ride has been cancelled.";

        notificationService.notifyRideCancelled(
                event.getRiderId(),
                event.getDriverId(),
                event.getRideId(),
                reason
        );
    }
}
