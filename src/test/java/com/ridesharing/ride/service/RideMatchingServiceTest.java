package com.ridesharing.ride.service;

import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.driver.service.DriverLocationService;
import com.ridesharing.driver.service.DriverLocationService.DriverLocationResult;
import com.ridesharing.ride.model.Ride;
import com.ridesharing.ride.repository.RideRepository;
import com.ridesharing.shared.enums.DriverAvailability;
import com.ridesharing.shared.enums.RideStatus;
import com.ridesharing.shared.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RideMatchingServiceTest {

    @Mock
    private DriverLocationService driverLocationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RideRepository rideRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private SetOperations<String, Object> setOperations;

    private RideMatchingService rideMatchingService;

    private User rider;
    private User driver;
    private Ride ride;

    @BeforeEach
    void setUp() {
        Executor syncExecutor = Runnable::run;
        rideMatchingService = new RideMatchingService(
                driverLocationService, userRepository, rideRepository,
                kafkaTemplate, redisTemplate, syncExecutor
        );

        rider = User.builder()
                .id(1L).name("Test Rider").email("rider@test.com")
                .phone("1234567890").role(UserRole.RIDER).active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        driver = User.builder()
                .id(10L).name("Nearby Driver").email("driver@test.com")
                .phone("9876543210").role(UserRole.DRIVER).active(true)
                .availability(DriverAvailability.ONLINE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        ride = Ride.builder()
                .id(100L).rider(rider)
                .pickupLatitude(12.97).pickupLongitude(77.59)
                .dropoffLatitude(12.93).dropoffLongitude(77.62)
                .vehicleType("AUTO").status(RideStatus.REQUESTED)
                .estimatedFare(new BigDecimal("105.00"))
                .surgeMultiplier(BigDecimal.ONE)
                .requestedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("Attempt Matching")
    class AttemptMatchingTests {

        @Test
        @DisplayName("Should match nearest available driver successfully")
        void shouldMatchNearestDriver() {
            DriverLocationResult nearby = new DriverLocationResult(10L, 1.5);
            when(driverLocationService.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(List.of(nearby));
            when(userRepository.findById(10L)).thenReturn(Optional.of(driver));
            when(rideRepository.existsByDriverIdAndStatusIn(eq(10L), any())).thenReturn(false);
            when(rideRepository.save(any(Ride.class))).thenReturn(ride);
            when(userRepository.save(any(User.class))).thenReturn(driver);

            boolean result = rideMatchingService.attemptMatching(ride);

            assertTrue(result);
            assertEquals(RideStatus.ACCEPTED, ride.getStatus());
            assertNotNull(ride.getAcceptedAt());
            assertEquals(driver, ride.getDriver());
            assertEquals(DriverAvailability.BUSY, driver.getAvailability());
            verify(driverLocationService).removeDriverLocation(10L);
        }

        @Test
        @DisplayName("Should skip driver who is OFFLINE")
        void shouldSkipOfflineDriver() {
            driver.setAvailability(DriverAvailability.OFFLINE);
            DriverLocationResult nearby = new DriverLocationResult(10L, 1.5);
            when(driverLocationService.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(List.of(nearby));
            when(userRepository.findById(10L)).thenReturn(Optional.of(driver));

            boolean result = rideMatchingService.attemptMatching(ride);

            assertFalse(result);
        }

        @Test
        @DisplayName("Should skip driver who is already on an active ride")
        void shouldSkipBusyDriver() {
            DriverLocationResult nearby = new DriverLocationResult(10L, 1.5);
            when(driverLocationService.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(List.of(nearby));
            when(userRepository.findById(10L)).thenReturn(Optional.of(driver));
            when(rideRepository.existsByDriverIdAndStatusIn(eq(10L), any())).thenReturn(true);

            boolean result = rideMatchingService.attemptMatching(ride);

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when no nearby drivers found")
        void shouldReturnFalseWhenNoDrivers() {
            when(driverLocationService.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(Collections.emptyList());

            boolean result = rideMatchingService.attemptMatching(ride);

            assertFalse(result);
        }

        @Test
        @DisplayName("Should try second driver when first is unavailable")
        void shouldTryNextDriverWhenFirstUnavailable() {
            User secondDriver = User.builder()
                    .id(20L).name("Second Driver").email("driver2@test.com")
                    .phone("5555555555").role(UserRole.DRIVER).active(true)
                    .availability(DriverAvailability.ONLINE)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();

            driver.setAvailability(DriverAvailability.OFFLINE);

            DriverLocationResult nearby1 = new DriverLocationResult(10L, 1.0);
            DriverLocationResult nearby2 = new DriverLocationResult(20L, 2.0);

            when(driverLocationService.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(List.of(nearby1, nearby2));
            when(userRepository.findById(10L)).thenReturn(Optional.of(driver));
            when(userRepository.findById(20L)).thenReturn(Optional.of(secondDriver));
            when(rideRepository.existsByDriverIdAndStatusIn(eq(20L), any())).thenReturn(false);
            when(rideRepository.save(any(Ride.class))).thenReturn(ride);
            when(userRepository.save(any(User.class))).thenReturn(secondDriver);

            boolean result = rideMatchingService.attemptMatching(ride);

            assertTrue(result);
            assertEquals(secondDriver, ride.getDriver());
        }
    }
}
