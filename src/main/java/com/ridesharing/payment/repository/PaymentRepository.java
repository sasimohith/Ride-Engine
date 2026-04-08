package com.ridesharing.payment.repository;

import com.ridesharing.payment.model.Payment;
import com.ridesharing.shared.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByRideId(Long rideId);

    boolean existsByRideId(Long rideId);

    List<Payment> findByRiderIdOrderByCreatedAtDesc(Long riderId);

    List<Payment> findByDriverIdOrderByCreatedAtDesc(Long driverId);

    List<Payment> findByStatus(PaymentStatus status);
}
