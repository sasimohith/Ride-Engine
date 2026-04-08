package com.ridesharing.notification.consumer;

import com.ridesharing.notification.service.NotificationService;
import com.ridesharing.shared.kafka.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes driver-related events from Kafka (e.g., admin approval).
 */
@Component
public class DriverEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DriverEventConsumer.class);

    private final NotificationService notificationService;

    public DriverEventConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_APPROVED, groupId = "notification-group")
    public void onDriverApproved(Map<String, Object> event) {
        Long driverId = ((Number) event.get("driverId")).longValue();
        String status = (String) event.get("status");

        log.info("Consumed DRIVER_APPROVED event: driverId={}, status={}", driverId, status);

        notificationService.notifyDriverApprovalStatus(driverId, status);
    }
}
