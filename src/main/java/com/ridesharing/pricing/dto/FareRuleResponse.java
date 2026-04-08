package com.ridesharing.pricing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response DTO for returning fare rule details (admin view / fare card).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FareRuleResponse {

    private Long id;
    private String vehicleType;
    private BigDecimal baseFare;
    private BigDecimal perKmRate;
    private BigDecimal perMinuteRate;
    private BigDecimal minimumFare;
    private boolean active;
}
