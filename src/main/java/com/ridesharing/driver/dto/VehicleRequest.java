package com.ridesharing.driver.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the client sends to POST /api/driver/vehicle.
 * Driver adds their vehicle details after registration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleRequest {

    @NotBlank(message = "Vehicle type is required (AUTO, BIKE, CAR)")
    private String vehicleType;

    @NotBlank(message = "Plate number is required")
    private String plateNumber;

    @NotBlank(message = "Vehicle model is required")
    private String model;

    @NotBlank(message = "Vehicle color is required")
    private String color;
}
