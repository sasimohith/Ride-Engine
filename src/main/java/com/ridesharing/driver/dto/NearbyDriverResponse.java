package com.ridesharing.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What we return when a rider asks "find drivers near me".
 * Each nearby driver includes their ID, name, vehicle info, and distance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NearbyDriverResponse {

    private Long driverId;
    private String name;
    private String vehicleType;
    private String plateNumber;
    private String vehicleModel;
    private String vehicleColor;
    private double distanceInKm;
}
