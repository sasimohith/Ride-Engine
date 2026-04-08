package com.ridesharing.ride.dto;

import com.ridesharing.shared.enums.RideStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What the rider/driver sees about a ride.
 * Contains full ride details, fare breakdown, and driver info.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RideResponseDto {

    private Long rideId;
    private Long riderId;
    private String riderName;

    private Long driverId;
    private String driverName;
    private String driverPhone;

    private double pickupLatitude;
    private double pickupLongitude;
    private String pickupAddress;

    private double dropoffLatitude;
    private double dropoffLongitude;
    private String dropoffAddress;

    private String vehicleType;
    private RideStatus status;

    private Double distanceKm;
    private Double estimatedTimeMin;
    private BigDecimal estimatedFare;
    private BigDecimal actualFare;
    private BigDecimal surgeMultiplier;

    private LocalDateTime requestedAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
}
