package com.ridesharing.ride.service;

import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.pricing.dto.FareEstimateRequest;
import com.ridesharing.pricing.dto.FareEstimateResponse;
import com.ridesharing.pricing.service.PricingService;
import com.ridesharing.ride.dto.RideRequestDto;
import com.ridesharing.ride.dto.RideResponseDto;
import com.ridesharing.ride.events.RideEvent;
import com.ridesharing.ride.model.Ride;
import com.ridesharing.ride.repository.RideRepository;
import com.ridesharing.shared.enums.RideStatus;
import com.ridesharing.shared.enums.UserRole;
import com.ridesharing.shared.exceptions.BadRequestException;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import com.ridesharing.shared.kafka.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates the full ride lifecycle:
 *   1. Request a ride (rider)
 *   2. Start a ride (driver)
 *   3. Complete a ride (driver)
 *   4. Cancel a ride (rider or driver)
 *   5. Get ride history
 *
 * This service coordinates between:
 *   - PricingService (fare calculation)
 *   - RideMatchingService (async driver matching)
 *   - KafkaTemplate (event publishing)
 */
@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);

    private final RideRepository rideRepository;
    private final UserRepository userRepository;
    private final PricingService pricingService;
    private final RideMatchingService rideMatchingService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public RideService(RideRepository rideRepository,
                       UserRepository userRepository,
                       PricingService pricingService,
                       RideMatchingService rideMatchingService,
                       KafkaTemplate<String, Object> kafkaTemplate) {
        this.rideRepository = rideRepository;
        this.userRepository = userRepository;
        this.pricingService = pricingService;
        this.rideMatchingService = rideMatchingService;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Step 1: Rider requests a ride.
     * - Validates rider isn't already on a ride
     * - Calculates fare estimate via PricingService
     * - Saves ride with status REQUESTED
     * - Publishes ride-requested Kafka event
     * - Triggers async driver matching
     */
    public RideResponseDto requestRide(Long riderId, RideRequestDto request) {
        User rider = userRepository.findById(riderId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", riderId));

        if (rider.getRole() != UserRole.RIDER) {
            throw new BadRequestException("Only riders can request rides");
        }

        // Check if rider already has an active ride
        boolean hasActiveRide = rideRepository.existsByRiderIdAndStatusIn(
                riderId, List.of(RideStatus.REQUESTED, RideStatus.ACCEPTED, RideStatus.IN_PROGRESS));
        if (hasActiveRide) {
            throw new BadRequestException("You already have an active ride");
        }

        // Get fare estimate from Pricing Module
        FareEstimateResponse fareEstimate = pricingService.estimateFare(
                FareEstimateRequest.builder()
                        .pickupLatitude(request.getPickupLatitude())
                        .pickupLongitude(request.getPickupLongitude())
                        .dropoffLatitude(request.getDropoffLatitude())
                        .dropoffLongitude(request.getDropoffLongitude())
                        .vehicleType(request.getVehicleType())
                        .build()
        );

        // Create and save the ride
        Ride ride = Ride.builder()
                .rider(rider)
                .pickupLatitude(request.getPickupLatitude())
                .pickupLongitude(request.getPickupLongitude())
                .pickupAddress(request.getPickupAddress())
                .dropoffLatitude(request.getDropoffLatitude())
                .dropoffLongitude(request.getDropoffLongitude())
                .dropoffAddress(request.getDropoffAddress())
                .vehicleType(request.getVehicleType().toUpperCase())
                .status(RideStatus.REQUESTED)
                .distanceKm(fareEstimate.getDistanceInKm())
                .estimatedTimeMin(fareEstimate.getEstimatedTimeInMinutes())
                .estimatedFare(fareEstimate.getEstimatedFare())
                .surgeMultiplier(fareEstimate.getSurgeMultiplier())
                .build();

        ride = rideRepository.save(ride);

        // Publish event + trigger async matching
        publishRideEvent(ride, KafkaTopics.RIDE_REQUESTED);
        rideMatchingService.matchDriverForRide(ride);

        log.info("Ride {} requested by rider {} — fare estimate: {}",
                ride.getId(), riderId, fareEstimate.getEstimatedFare());

        return toResponseDto(ride);
    }

    /**
     * Step 2: Driver starts the ride (picks up the rider).
     * Transition: ACCEPTED → IN_PROGRESS
     */
    public RideResponseDto startRide(Long driverId, Long rideId) {
        Ride ride = rideRepository.findByIdAndDriverId(rideId, driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride", "id", rideId));

        if (ride.getStatus() != RideStatus.ACCEPTED) {
            throw new BadRequestException("Ride can only be started when status is ACCEPTED");
        }

        ride.setStatus(RideStatus.IN_PROGRESS);
        ride.setStartedAt(LocalDateTime.now());
        ride = rideRepository.save(ride);

        log.info("Ride {} started by driver {}", rideId, driverId);
        return toResponseDto(ride);
    }

    /**
     * Step 3: Driver completes the ride (reaches destination).
     * Transition: IN_PROGRESS → COMPLETED
     * Publishes ride-completed event (consumed by Payment + Notification modules).
     */
    public RideResponseDto completeRide(Long driverId, Long rideId) {
        Ride ride = rideRepository.findByIdAndDriverId(rideId, driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride", "id", rideId));

        if (ride.getStatus() != RideStatus.IN_PROGRESS) {
            throw new BadRequestException("Ride can only be completed when status is IN_PROGRESS");
        }

        ride.setStatus(RideStatus.COMPLETED);
        ride.setCompletedAt(LocalDateTime.now());
        ride.setActualFare(ride.getEstimatedFare());
        ride = rideRepository.save(ride);

        // Make driver available again
        User driver = ride.getDriver();
        driver.setAvailability(com.ridesharing.shared.enums.DriverAvailability.ONLINE);
        userRepository.save(driver);

        publishRideEvent(ride, KafkaTopics.RIDE_COMPLETED);

        log.info("Ride {} completed by driver {} — fare: {}", rideId, driverId, ride.getActualFare());
        return toResponseDto(ride);
    }

    /**
     * Step 4: Cancel a ride (by rider or driver).
     * Allowed transitions: REQUESTED → CANCELLED, ACCEPTED → CANCELLED
     */
    public RideResponseDto cancelRide(Long userId, Long rideId) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride", "id", rideId));

        // Verify the user is the rider or the assigned driver
        boolean isRider = ride.getRider().getId().equals(userId);
        boolean isDriver = ride.getDriver() != null && ride.getDriver().getId().equals(userId);
        if (!isRider && !isDriver) {
            throw new BadRequestException("You are not part of this ride");
        }

        if (ride.getStatus() != RideStatus.REQUESTED && ride.getStatus() != RideStatus.ACCEPTED) {
            throw new BadRequestException("Ride can only be cancelled when status is REQUESTED or ACCEPTED");
        }

        ride.setStatus(RideStatus.CANCELLED);
        ride.setCancelledAt(LocalDateTime.now());
        ride = rideRepository.save(ride);

        // If a driver was assigned, make them available again
        if (ride.getDriver() != null) {
            User driver = ride.getDriver();
            driver.setAvailability(com.ridesharing.shared.enums.DriverAvailability.ONLINE);
            userRepository.save(driver);
        }

        publishRideEvent(ride, KafkaTopics.RIDE_CANCELLED);

        log.info("Ride {} cancelled by user {}", rideId, userId);
        return toResponseDto(ride);
    }

    /**
     * Get a specific ride by ID (for any authenticated user who is part of the ride).
     */
    public RideResponseDto getRide(Long userId, Long rideId) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride", "id", rideId));

        boolean isRider = ride.getRider().getId().equals(userId);
        boolean isDriver = ride.getDriver() != null && ride.getDriver().getId().equals(userId);
        if (!isRider && !isDriver) {
            throw new BadRequestException("You are not part of this ride");
        }

        return toResponseDto(ride);
    }

    /**
     * Get ride history for a rider.
     */
    public List<RideResponseDto> getRiderHistory(Long riderId) {
        return rideRepository.findByRiderIdOrderByCreatedAtDesc(riderId).stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    /**
     * Get ride history for a driver.
     */
    public List<RideResponseDto> getDriverHistory(Long driverId) {
        return rideRepository.findByDriverIdOrderByCreatedAtDesc(driverId).stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    private void publishRideEvent(Ride ride, String topic) {
        RideEvent event = RideEvent.builder()
                .rideId(ride.getId())
                .riderId(ride.getRider().getId())
                .driverId(ride.getDriver() != null ? ride.getDriver().getId() : null)
                .vehicleType(ride.getVehicleType())
                .status(ride.getStatus().name())
                .pickupLatitude(ride.getPickupLatitude())
                .pickupLongitude(ride.getPickupLongitude())
                .dropoffLatitude(ride.getDropoffLatitude())
                .dropoffLongitude(ride.getDropoffLongitude())
                .estimatedFare(ride.getEstimatedFare())
                .actualFare(ride.getActualFare())
                .surgeMultiplier(ride.getSurgeMultiplier())
                .timestamp(System.currentTimeMillis())
                .build();

        kafkaTemplate.send(topic, ride.getId().toString(), event);
        log.info("Published {} event for ride {}", topic, ride.getId());
    }

    private RideResponseDto toResponseDto(Ride ride) {
        return RideResponseDto.builder()
                .rideId(ride.getId())
                .riderId(ride.getRider().getId())
                .riderName(ride.getRider().getName())
                .driverId(ride.getDriver() != null ? ride.getDriver().getId() : null)
                .driverName(ride.getDriver() != null ? ride.getDriver().getName() : null)
                .driverPhone(ride.getDriver() != null ? ride.getDriver().getPhone() : null)
                .pickupLatitude(ride.getPickupLatitude())
                .pickupLongitude(ride.getPickupLongitude())
                .pickupAddress(ride.getPickupAddress())
                .dropoffLatitude(ride.getDropoffLatitude())
                .dropoffLongitude(ride.getDropoffLongitude())
                .dropoffAddress(ride.getDropoffAddress())
                .vehicleType(ride.getVehicleType())
                .status(ride.getStatus())
                .distanceKm(ride.getDistanceKm())
                .estimatedTimeMin(ride.getEstimatedTimeMin())
                .estimatedFare(ride.getEstimatedFare())
                .actualFare(ride.getActualFare())
                .surgeMultiplier(ride.getSurgeMultiplier())
                .requestedAt(ride.getRequestedAt())
                .acceptedAt(ride.getAcceptedAt())
                .startedAt(ride.getStartedAt())
                .completedAt(ride.getCompletedAt())
                .cancelledAt(ride.getCancelledAt())
                .build();
    }
}
