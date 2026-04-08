package com.ridesharing.driver.repository;

import com.ridesharing.driver.model.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Database access for Vehicle entity.
 * Spring auto-generates: findByDriverId → SELECT * FROM vehicles WHERE driver_id = ?
 */
@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    /**
     * Finds the vehicle belonging to a specific driver.
     * Each driver has at most one vehicle (OneToOne relationship).
     */
    Optional<Vehicle> findByDriverId(Long driverId);

    boolean existsByPlateNumber(String plateNumber);
}
