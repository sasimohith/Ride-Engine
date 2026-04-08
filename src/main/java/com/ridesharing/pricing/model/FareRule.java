package com.ridesharing.pricing.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA Entity mapped to the fare_rules table.
 * Each row defines the pricing formula for one vehicle type.
 *
 * Formula: fare = (baseFare + distance * perKmRate + time * perMinuteRate) * surgeMultiplier
 * Final fare = max(calculatedFare, minimumFare)
 */
@Entity
@Table(name = "fare_rules")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FareRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_type", nullable = false, unique = true, length = 30)
    private String vehicleType;

    @Column(name = "base_fare", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseFare;

    @Column(name = "per_km_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal perKmRate;

    @Column(name = "per_minute_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal perMinuteRate;

    @Column(name = "minimum_fare", nullable = false, precision = 10, scale = 2)
    private BigDecimal minimumFare;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
