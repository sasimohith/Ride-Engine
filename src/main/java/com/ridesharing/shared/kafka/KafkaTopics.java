package com.ridesharing.shared.kafka;

/**
 * Central registry of ALL Kafka topic names used in the application.
 * Every module that publishes or consumes events references this class.
 *
 * WHY a central file?
 *   - Prevents typos: "ride-reqested" vs "ride-requested" would be a silent bug
 *   - Single source of truth: grep this file to see all events in the system
 *   - Easy to audit: in an interview, you can list all events from one place
 *
 * Topic naming convention: <module>-<event-name>
 */
public final class KafkaTopics {

    private KafkaTopics() {
        // Utility class — prevent instantiation
    }

    // ── RIDE EVENTS ──
    // Published when a rider submits a ride request.
    // Consumed by: RideMatchingService (to find nearby drivers)
    public static final String RIDE_REQUESTED = "ride-requested";

    // Published when a ride is completed (driver ends the trip).
    // Consumed by: PaymentService (create payment), NotificationService (send summary)
    public static final String RIDE_COMPLETED = "ride-completed";

    // Published when a ride is cancelled by rider or system (no driver found).
    // Consumed by: NotificationService (inform rider/driver)
    public static final String RIDE_CANCELLED = "ride-cancelled";

    // Published when a driver accepts a ride request.
    // Consumed by: NotificationService (notify rider that driver is coming)
    public static final String RIDE_ACCEPTED = "ride-accepted";

    // ── DRIVER EVENTS ──
    // Published when admin approves or rejects a driver registration.
    // Consumed by: NotificationService (inform driver of approval status)
    public static final String DRIVER_APPROVED = "driver-approved";

    // ── DEAD LETTER TOPICS ──
    // Failed events land here after max retries. For manual investigation.
    public static final String RIDE_REQUESTED_DLT = "ride-requested-dlt";
    public static final String RIDE_COMPLETED_DLT = "ride-completed-dlt";
}
