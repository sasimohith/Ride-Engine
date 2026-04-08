package com.ridesharing.pricing.repository;

import com.ridesharing.pricing.model.FareRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FareRuleRepository extends JpaRepository<FareRule, Long> {

    Optional<FareRule> findByVehicleTypeAndActiveTrue(String vehicleType);

    List<FareRule> findAllByActiveTrue();
}
