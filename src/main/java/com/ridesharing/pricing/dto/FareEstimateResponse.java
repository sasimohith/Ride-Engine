package com.ridesharing.pricing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response DTO containing the full fare breakdown.
 * The frontend shows this to the rider before they confirm the ride.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FareEstimateResponse {

    private String vehicleType;
    private double distanceInKm;
    private double estimatedTimeInMinutes;

    private BigDecimal baseFare;
    private BigDecimal distanceCharge;
    private BigDecimal timeCharge;
    private BigDecimal surgeMultiplier;
    private BigDecimal estimatedFare;
    private BigDecimal minimumFare;

    private boolean surgeActive;
}
