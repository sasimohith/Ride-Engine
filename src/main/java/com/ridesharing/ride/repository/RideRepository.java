package com.ridesharing.ride.repository;

import com.ridesharing.ride.model.Ride;
import com.ridesharing.shared.enums.RideStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RideRepository extends JpaRepository<Ride, Long> {

    List<Ride> findByRiderIdOrderByCreatedAtDesc(Long riderId);

    List<Ride> findByDriverIdOrderByCreatedAtDesc(Long driverId);

    Optional<Ride> findByIdAndRiderId(Long id, Long riderId);

    Optional<Ride> findByIdAndDriverId(Long id, Long driverId);

    List<Ride> findByRiderIdAndStatus(Long riderId, RideStatus status);

    List<Ride> findByDriverIdAndStatus(Long driverId, RideStatus status);

    boolean existsByRiderIdAndStatusIn(Long riderId, java.util.Collection<RideStatus> statuses);

    boolean existsByDriverIdAndStatusIn(Long driverId, java.util.Collection<RideStatus> statuses);
}
