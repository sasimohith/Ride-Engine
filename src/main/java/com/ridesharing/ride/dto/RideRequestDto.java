package com.ridesharing.ride.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the rider sends when requesting a ride.
 * Contains pickup/dropoff coordinates and desired vehicle type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RideRequestDto {

    @NotNull(message = "Pickup latitude is required")
    private Double pickupLatitude;

    @NotNull(message = "Pickup longitude is required")
    private Double pickupLongitude;

    private String pickupAddress;

    @NotNull(message = "Dropoff latitude is required")
    private Double dropoffLatitude;

    @NotNull(message = "Dropoff longitude is required")
    private Double dropoffLongitude;

    private String dropoffAddress;

    @NotBlank(message = "Vehicle type is required")
    private String vehicleType;
}
