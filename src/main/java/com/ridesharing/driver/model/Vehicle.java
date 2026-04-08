package com.ridesharing.driver.model;

import com.ridesharing.auth.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The Vehicle entity — maps to the "vehicles" table in PostgreSQL.
 * Each driver has exactly ONE vehicle. A vehicle belongs to exactly ONE driver.
 *
 * WHY a separate table (not columns in users)?
 *   - Not every user has a vehicle (riders don't)
 *   - Vehicle data is only relevant for drivers
 *   - Keeps the users table clean and focused
 *   - Vehicle could be changed without touching the user record
 *
 * @OneToOne with User: one vehicle → one driver, one driver → one vehicle
 */
@Entity
@Table(name = "vehicles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The driver who owns this vehicle.
     * @OneToOne = this vehicle belongs to exactly one user.
     * @JoinColumn(name = "driver_id") = the foreign key column in the vehicles table.
     * PostgreSQL will enforce: driver_id MUST exist in the users table.
     */
    @OneToOne
    @JoinColumn(name = "driver_id", nullable = false, unique = true)
    private User driver;

    /**
     * Type of vehicle: AUTO, BIKE, CAR.
     * Determines which fare rule applies to rides with this driver.
     */
    @Column(name = "vehicle_type", nullable = false)
    private String vehicleType;

    /**
     * Registration plate number: "TN 01 AB 1234".
     * Must be unique — two vehicles can't share a plate.
     */
    @Column(name = "plate_number", nullable = false, unique = true)
    private String plateNumber;

    /**
     * Vehicle model name: "Honda Activa", "Bajaj Pulsar", "Maruti Swift".
     */
    @Column(nullable = false)
    private String model;

    /**
     * Vehicle color: "Red", "Black", "White".
     * Rider needs this to identify the vehicle at pickup.
     */
    @Column(nullable = false)
    private String color;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @jakarta.persistence.PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
