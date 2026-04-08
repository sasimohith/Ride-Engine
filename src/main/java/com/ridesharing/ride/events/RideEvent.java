package com.ridesharing.ride.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Kafka event payload for ride lifecycle events.
 * Published to topics: ride-requested, ride-accepted, ride-completed, ride-cancelled.
 * Consumed by: NotificationModule, PaymentModule (future).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RideEvent {

    private Long rideId;
    private Long riderId;
    private Long driverId;
    private String vehicleType;
    private String status;

    private double pickupLatitude;
    private double pickupLongitude;
    private double dropoffLatitude;
    private double dropoffLongitude;

    private BigDecimal estimatedFare;
    private BigDecimal actualFare;
    private BigDecimal surgeMultiplier;

    private long timestamp;
}
