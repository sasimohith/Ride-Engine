package com.ridesharing.ride.service;

import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.driver.service.DriverLocationService;
import com.ridesharing.driver.service.DriverLocationService.DriverLocationResult;
import com.ridesharing.ride.events.RideEvent;
import com.ridesharing.ride.model.Ride;
import com.ridesharing.ride.repository.RideRepository;
import com.ridesharing.shared.enums.DriverAvailability;
import com.ridesharing.shared.enums.RideStatus;
import com.ridesharing.shared.enums.UserRole;
import com.ridesharing.shared.kafka.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Handles async driver matching when a ride is requested.
 *
 * WHAT: When a rider requests a ride, this service runs IN THE BACKGROUND
 *       to find the nearest available driver and assign them to the ride.
 *
 * WHY:  Driver matching can take seconds (searching, checking availability).
 *       We don't want the rider's HTTP request to hang while we search.
 *       So we return "REQUESTED" immediately and match asynchronously.
 *
 * HOW:  Uses CompletableFuture.supplyAsync() to run on our custom thread pool.
 *       Steps: find nearby drivers → filter available ones → assign best match.
 *       If no driver found, the ride is cancelled after max retries.
 */
@Service
public class RideMatchingService {

    private static final Logger log = LoggerFactory.getLogger(RideMatchingService.class);

    private static final double SEARCH_RADIUS_KM = 5.0;
    private static final int MAX_MATCHING_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 5000;
    private static final String PENDING_REQUESTS_KEY = "ride:pending-requests";

    private final DriverLocationService driverLocationService;
    private final UserRepository userRepository;
    private final RideRepository rideRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final Executor asyncExecutor;

    public RideMatchingService(DriverLocationService driverLocationService,
                               UserRepository userRepository,
                               RideRepository rideRepository,
                               KafkaTemplate<String, Object> kafkaTemplate,
                               RedisTemplate<String, Object> redisTemplate,
                               Executor asyncExecutor) {
        this.driverLocationService = driverLocationService;
        this.userRepository = userRepository;
        this.rideRepository = rideRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * Starts async driver matching for a ride.
     * Called by RideService after saving the ride with status REQUESTED.
     *
     * Uses CompletableFuture to run on the async thread pool.
     * The calling thread (HTTP request thread) is NOT blocked.
     */
    @Async("asyncExecutor")
    public void matchDriverForRide(Ride ride) {
        // Track this as a pending request (used by SurgePricingScheduler)
        redisTemplate.opsForSet().add(PENDING_REQUESTS_KEY, ride.getId().toString());

        log.info("Starting driver matching for ride {} (pickup: {}, {})",
                ride.getId(), ride.getPickupLatitude(), ride.getPickupLongitude());

        CompletableFuture
                .supplyAsync(() -> attemptMatching(ride), asyncExecutor)
                .thenAccept(matched -> {
                    // Remove from pending requests regardless of outcome
                    redisTemplate.opsForSet().remove(PENDING_REQUESTS_KEY, ride.getId().toString());

                    if (!matched) {
                        cancelRideNoDriverFound(ride);
                    }
                })
                .exceptionally(ex -> {
                    log.error("Driver matching failed for ride {}", ride.getId(), ex);
                    redisTemplate.opsForSet().remove(PENDING_REQUESTS_KEY, ride.getId().toString());
                    cancelRideNoDriverFound(ride);
                    return null;
                });
    }

    /**
     * Attempts to find and assign a driver, with retries.
     * Each retry searches for nearby drivers and checks their availability in the DB.
     *
     * @return true if a driver was successfully assigned, false if all retries exhausted
     */
    public boolean attemptMatching(Ride ride) {
        for (int attempt = 1; attempt <= MAX_MATCHING_RETRIES; attempt++) {
            log.info("Matching attempt {}/{} for ride {}", attempt, MAX_MATCHING_RETRIES, ride.getId());

            List<DriverLocationResult> nearbyDrivers = driverLocationService.findNearbyDrivers(
                    ride.getPickupLatitude(),
                    ride.getPickupLongitude(),
                    SEARCH_RADIUS_KM
            );

            if (nearbyDrivers.isEmpty()) {
                log.info("No nearby drivers found for ride {} on attempt {}", ride.getId(), attempt);
                waitBeforeRetry(attempt);
                continue;
            }

            // Try each nearby driver (sorted by distance, nearest first)
            for (DriverLocationResult nearbyDriver : nearbyDrivers) {
                Optional<User> driverOpt = userRepository.findById(nearbyDriver.driverId());
                if (driverOpt.isEmpty()) continue;

                User driver = driverOpt.get();

                // Check: is the driver actually available?
                if (driver.getRole() != UserRole.DRIVER) continue;
                if (driver.getAvailability() != DriverAvailability.ONLINE) continue;
                if (!driver.isActive()) continue;

                // Check: is this driver already on another active ride?
                boolean onActiveRide = rideRepository.existsByDriverIdAndStatusIn(
                        driver.getId(),
                        List.of(RideStatus.ACCEPTED, RideStatus.IN_PROGRESS)
                );
                if (onActiveRide) continue;

                // Found a valid driver — assign them
                return assignDriver(ride, driver);
            }

            log.info("All nearby drivers busy for ride {} on attempt {}", ride.getId(), attempt);
            waitBeforeRetry(attempt);
        }

        return false;
    }

    /**
     * Assigns a driver to a ride: updates DB, sets driver to BUSY, publishes Kafka event.
     */
    private boolean assignDriver(Ride ride, User driver) {
        ride.setDriver(driver);
        ride.setStatus(RideStatus.ACCEPTED);
        ride.setAcceptedAt(LocalDateTime.now());
        rideRepository.save(ride);

        driver.setAvailability(DriverAvailability.BUSY);
        userRepository.save(driver);

        // Remove driver from available pool in Redis
        driverLocationService.removeDriverLocation(driver.getId());

        publishRideEvent(ride, KafkaTopics.RIDE_ACCEPTED);

        log.info("Ride {} matched with driver {} ({})",
                ride.getId(), driver.getId(), driver.getName());

        return true;
    }

    /**
     * Cancels a ride when no driver could be found after all retries.
     */
    private void cancelRideNoDriverFound(Ride ride) {
        ride.setStatus(RideStatus.CANCELLED);
        ride.setCancelledAt(LocalDateTime.now());
        rideRepository.save(ride);

        publishRideEvent(ride, KafkaTopics.RIDE_CANCELLED);

        log.warn("Ride {} cancelled — no driver found after {} attempts",
                ride.getId(), MAX_MATCHING_RETRIES);
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
                .surgeMultiplier(ride.getSurgeMultiplier())
                .timestamp(System.currentTimeMillis())
                .build();

        kafkaTemplate.send(topic, ride.getId().toString(), event);
        log.info("Published {} event for ride {}", topic, ride.getId());
    }

    private void waitBeforeRetry(int attempt) {
        if (attempt < MAX_MATCHING_RETRIES) {
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
