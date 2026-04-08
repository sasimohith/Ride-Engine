package com.ridesharing.notification.service;

import com.ridesharing.notification.dto.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Sends real-time notifications to riders and drivers via WebSocket.
 *
 * All methods are @Async — they run on the async thread pool,
 * so the caller (Kafka consumer) is never blocked by notification delivery.
 *
 * WebSocket topic conventions:
 *   /topic/rider/{riderId}     — notifications for a specific rider
 *   /topic/driver/{driverId}   — notifications for a specific driver
 *   /topic/ride/{rideId}       — notifications about a specific ride
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Notifies a rider that a driver has been found and is on the way.
     */
    @Async("asyncExecutor")
    public void notifyRiderDriverAccepted(Long riderId, Long rideId, Long driverId) {
        NotificationMessage notification = NotificationMessage.builder()
                .type("RIDE_ACCEPTED")
                .title("Driver Found!")
                .message("A driver has accepted your ride and is on the way.")
                .rideId(rideId)
                .recipientId(riderId)
                .data(Map.of("driverId", driverId))
                .timestamp(LocalDateTime.now())
                .build();

        sendToRider(riderId, notification);
        sendToRide(rideId, notification);
    }

    /**
     * Notifies a rider that the ride has started (driver picked them up).
     */
    @Async("asyncExecutor")
    public void notifyRideStarted(Long riderId, Long driverId, Long rideId) {
        NotificationMessage riderNotif = NotificationMessage.builder()
                .type("RIDE_STARTED")
                .title("Ride Started")
                .message("Your ride is now in progress. Enjoy the trip!")
                .rideId(rideId)
                .recipientId(riderId)
                .timestamp(LocalDateTime.now())
                .build();

        sendToRider(riderId, riderNotif);
        sendToRide(rideId, riderNotif);
    }

    /**
     * Notifies both rider and driver that the ride is complete.
     */
    @Async("asyncExecutor")
    public void notifyRideCompleted(Long riderId, Long driverId, Long rideId, String fare) {
        NotificationMessage riderNotif = NotificationMessage.builder()
                .type("RIDE_COMPLETED")
                .title("Ride Completed")
                .message("Your ride is complete. Fare: ₹" + fare)
                .rideId(rideId)
                .recipientId(riderId)
                .data(Map.of("fare", fare))
                .timestamp(LocalDateTime.now())
                .build();

        NotificationMessage driverNotif = NotificationMessage.builder()
                .type("RIDE_COMPLETED")
                .title("Trip Finished")
                .message("Trip completed. Earnings: ₹" + fare)
                .rideId(rideId)
                .recipientId(driverId)
                .data(Map.of("fare", fare))
                .timestamp(LocalDateTime.now())
                .build();

        sendToRider(riderId, riderNotif);
        sendToDriver(driverId, driverNotif);
        sendToRide(rideId, riderNotif);
    }

    /**
     * Notifies the rider that the ride was cancelled (no driver found or manual cancel).
     */
    @Async("asyncExecutor")
    public void notifyRideCancelled(Long riderId, Long driverId, Long rideId, String reason) {
        NotificationMessage riderNotif = NotificationMessage.builder()
                .type("RIDE_CANCELLED")
                .title("Ride Cancelled")
                .message(reason)
                .rideId(rideId)
                .recipientId(riderId)
                .timestamp(LocalDateTime.now())
                .build();

        sendToRider(riderId, riderNotif);
        sendToRide(rideId, riderNotif);

        if (driverId != null) {
            NotificationMessage driverNotif = NotificationMessage.builder()
                    .type("RIDE_CANCELLED")
                    .title("Ride Cancelled")
                    .message("The ride has been cancelled.")
                    .rideId(rideId)
                    .recipientId(driverId)
                    .timestamp(LocalDateTime.now())
                    .build();

            sendToDriver(driverId, driverNotif);
        }
    }

    /**
     * Notifies a driver about their approval status change.
     */
    @Async("asyncExecutor")
    public void notifyDriverApprovalStatus(Long driverId, String status) {
        String message = "APPROVED".equals(status)
                ? "Congratulations! Your driver registration has been approved. You can now go online."
                : "Your driver registration has been rejected. Please contact support.";

        NotificationMessage notification = NotificationMessage.builder()
                .type("DRIVER_APPROVAL")
                .title("Registration " + status)
                .message(message)
                .recipientId(driverId)
                .data(Map.of("approvalStatus", status))
                .timestamp(LocalDateTime.now())
                .build();

        sendToDriver(driverId, notification);
    }

    private void sendToRider(Long riderId, NotificationMessage notification) {
        String destination = "/topic/rider/" + riderId;
        messagingTemplate.convertAndSend(destination, notification);
        log.info("Notification sent to rider {}: type={}", riderId, notification.getType());
    }

    private void sendToDriver(Long driverId, NotificationMessage notification) {
        String destination = "/topic/driver/" + driverId;
        messagingTemplate.convertAndSend(destination, notification);
        log.info("Notification sent to driver {}: type={}", driverId, notification.getType());
    }

    private void sendToRide(Long rideId, NotificationMessage notification) {
        String destination = "/topic/ride/" + rideId;
        messagingTemplate.convertAndSend(destination, notification);
        log.debug("Notification sent to ride topic {}", rideId);
    }
}
