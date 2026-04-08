package com.ridesharing.ride.service;

import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.pricing.dto.FareEstimateResponse;
import com.ridesharing.pricing.service.PricingService;
import com.ridesharing.ride.dto.RideRequestDto;
import com.ridesharing.ride.dto.RideResponseDto;
import com.ridesharing.ride.model.Ride;
import com.ridesharing.ride.repository.RideRepository;
import com.ridesharing.shared.enums.DriverAvailability;
import com.ridesharing.shared.enums.RideStatus;
import com.ridesharing.shared.enums.UserRole;
import com.ridesharing.shared.exceptions.BadRequestException;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import com.ridesharing.shared.kafka.KafkaTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RideServiceTest {

    @Mock
    private RideRepository rideRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PricingService pricingService;

    @Mock
    private RideMatchingService rideMatchingService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private RideService rideService;

    private User rider;
    private User driver;
    private Ride ride;
    private RideRequestDto rideRequest;

    @BeforeEach
    void setUp() {
        rider = User.builder()
                .id(1L).name("Test Rider").email("rider@test.com")
                .phone("1234567890").role(UserRole.RIDER).active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        driver = User.builder()
                .id(2L).name("Test Driver").email("driver@test.com")
                .phone("9876543210").role(UserRole.DRIVER).active(true)
                .availability(DriverAvailability.BUSY)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        ride = Ride.builder()
                .id(100L).rider(rider).driver(driver)
                .pickupLatitude(12.97).pickupLongitude(77.59)
                .dropoffLatitude(12.93).dropoffLongitude(77.62)
                .vehicleType("AUTO").status(RideStatus.ACCEPTED)
                .distanceKm(5.0).estimatedTimeMin(12.0)
                .estimatedFare(new BigDecimal("105.00"))
                .surgeMultiplier(BigDecimal.ONE)
                .requestedAt(LocalDateTime.now())
                .acceptedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        rideRequest = RideRequestDto.builder()
                .pickupLatitude(12.97).pickupLongitude(77.59)
                .dropoffLatitude(12.93).dropoffLongitude(77.62)
                .vehicleType("AUTO")
                .build();
    }

    @Nested
    @DisplayName("Request Ride")
    class RequestRideTests {

        @Test
        @DisplayName("Should create a ride with fare estimate and trigger matching")
        void shouldCreateRideSuccessfully() {
            FareEstimateResponse fare = FareEstimateResponse.builder()
                    .vehicleType("AUTO").distanceInKm(5.0).estimatedTimeInMinutes(12.0)
                    .estimatedFare(new BigDecimal("105.00")).surgeMultiplier(BigDecimal.ONE)
                    .build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(rider));
            when(rideRepository.existsByRiderIdAndStatusIn(eq(1L), any())).thenReturn(false);
            when(pricingService.estimateFare(any())).thenReturn(fare);
            when(rideRepository.save(any(Ride.class))).thenAnswer(inv -> {
                Ride saved = inv.getArgument(0);
                saved.setId(100L);
                return saved;
            });

            RideResponseDto response = rideService.requestRide(1L, rideRequest);

            assertNotNull(response);
            assertEquals(RideStatus.REQUESTED, response.getStatus());
            assertEquals("AUTO", response.getVehicleType());
            verify(rideMatchingService).matchDriverForRide(any(Ride.class));
            verify(kafkaTemplate).send(eq(KafkaTopics.RIDE_REQUESTED), anyString(), any());
        }

        @Test
        @DisplayName("Should throw when rider already has an active ride")
        void shouldThrowWhenRiderHasActiveRide() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(rider));
            when(rideRepository.existsByRiderIdAndStatusIn(eq(1L), any())).thenReturn(true);

            assertThrows(BadRequestException.class,
                    () -> rideService.requestRide(1L, rideRequest));

            verify(rideRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw when user is not a rider")
        void shouldThrowWhenNotARider() {
            when(userRepository.findById(2L)).thenReturn(Optional.of(driver));

            assertThrows(BadRequestException.class,
                    () -> rideService.requestRide(2L, rideRequest));
        }

        @Test
        @DisplayName("Should throw when rider not found")
        void shouldThrowWhenRiderNotFound() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> rideService.requestRide(999L, rideRequest));
        }
    }

    @Nested
    @DisplayName("Start Ride")
    class StartRideTests {

        @Test
        @DisplayName("Should transition from ACCEPTED to IN_PROGRESS")
        void shouldStartRideSuccessfully() {
            when(rideRepository.findByIdAndDriverId(100L, 2L)).thenReturn(Optional.of(ride));
            when(rideRepository.save(any(Ride.class))).thenReturn(ride);

            RideResponseDto response = rideService.startRide(2L, 100L);

            assertEquals(RideStatus.IN_PROGRESS, ride.getStatus());
            assertNotNull(ride.getStartedAt());
            verify(rideRepository).save(ride);
        }

        @Test
        @DisplayName("Should throw when ride is not in ACCEPTED status")
        void shouldThrowWhenNotAccepted() {
            ride.setStatus(RideStatus.IN_PROGRESS);
            when(rideRepository.findByIdAndDriverId(100L, 2L)).thenReturn(Optional.of(ride));

            assertThrows(BadRequestException.class,
                    () -> rideService.startRide(2L, 100L));
        }

        @Test
        @DisplayName("Should throw when ride not found for driver")
        void shouldThrowWhenRideNotFoundForDriver() {
            when(rideRepository.findByIdAndDriverId(100L, 999L)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> rideService.startRide(999L, 100L));
        }
    }

    @Nested
    @DisplayName("Complete Ride")
    class CompleteRideTests {

        @Test
        @DisplayName("Should complete ride, set actual fare, and make driver available")
        void shouldCompleteRideSuccessfully() {
            ride.setStatus(RideStatus.IN_PROGRESS);
            when(rideRepository.findByIdAndDriverId(100L, 2L)).thenReturn(Optional.of(ride));
            when(rideRepository.save(any(Ride.class))).thenReturn(ride);
            when(userRepository.save(any(User.class))).thenReturn(driver);

            RideResponseDto response = rideService.completeRide(2L, 100L);

            assertEquals(RideStatus.COMPLETED, ride.getStatus());
            assertNotNull(ride.getCompletedAt());
            assertEquals(ride.getEstimatedFare(), ride.getActualFare());
            assertEquals(DriverAvailability.ONLINE, driver.getAvailability());
            verify(kafkaTemplate).send(eq(KafkaTopics.RIDE_COMPLETED), anyString(), any());
        }

        @Test
        @DisplayName("Should throw when ride is not in IN_PROGRESS status")
        void shouldThrowWhenNotInProgress() {
            ride.setStatus(RideStatus.ACCEPTED);
            when(rideRepository.findByIdAndDriverId(100L, 2L)).thenReturn(Optional.of(ride));

            assertThrows(BadRequestException.class,
                    () -> rideService.completeRide(2L, 100L));
        }
    }

    @Nested
    @DisplayName("Cancel Ride")
    class CancelRideTests {

        @Test
        @DisplayName("Should cancel a REQUESTED ride by rider")
        void shouldCancelRequestedRideByRider() {
            ride.setStatus(RideStatus.REQUESTED);
            ride.setDriver(null);
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
            when(rideRepository.save(any(Ride.class))).thenReturn(ride);

            RideResponseDto response = rideService.cancelRide(1L, 100L);

            assertEquals(RideStatus.CANCELLED, ride.getStatus());
            assertNotNull(ride.getCancelledAt());
            verify(kafkaTemplate).send(eq(KafkaTopics.RIDE_CANCELLED), anyString(), any());
        }

        @Test
        @DisplayName("Should cancel an ACCEPTED ride and make driver available")
        void shouldCancelAcceptedRideAndFreeDriver() {
            ride.setStatus(RideStatus.ACCEPTED);
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
            when(rideRepository.save(any(Ride.class))).thenReturn(ride);
            when(userRepository.save(any(User.class))).thenReturn(driver);

            rideService.cancelRide(2L, 100L);

            assertEquals(RideStatus.CANCELLED, ride.getStatus());
            assertEquals(DriverAvailability.ONLINE, driver.getAvailability());
        }

        @Test
        @DisplayName("Should throw when user is not part of the ride")
        void shouldThrowWhenNotPartOfRide() {
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));

            assertThrows(BadRequestException.class,
                    () -> rideService.cancelRide(999L, 100L));
        }

        @Test
        @DisplayName("Should throw when ride is already IN_PROGRESS")
        void shouldThrowWhenRideIsInProgress() {
            ride.setStatus(RideStatus.IN_PROGRESS);
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));

            assertThrows(BadRequestException.class,
                    () -> rideService.cancelRide(1L, 100L));
        }
    }

    @Nested
    @DisplayName("Get Ride")
    class GetRideTests {

        @Test
        @DisplayName("Should return ride details for the rider")
        void shouldReturnRideForRider() {
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));

            RideResponseDto response = rideService.getRide(1L, 100L);

            assertEquals(100L, response.getRideId());
            assertEquals("Test Rider", response.getRiderName());
        }

        @Test
        @DisplayName("Should return ride details for the driver")
        void shouldReturnRideForDriver() {
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));

            RideResponseDto response = rideService.getRide(2L, 100L);

            assertEquals(100L, response.getRideId());
        }
    }

    @Nested
    @DisplayName("Ride History")
    class RideHistoryTests {

        @Test
        @DisplayName("Should return rider's ride history")
        void shouldReturnRiderHistory() {
            when(rideRepository.findByRiderIdOrderByCreatedAtDesc(1L))
                    .thenReturn(List.of(ride));

            List<RideResponseDto> history = rideService.getRiderHistory(1L);

            assertEquals(1, history.size());
            assertEquals(100L, history.get(0).getRideId());
        }

        @Test
        @DisplayName("Should return driver's ride history")
        void shouldReturnDriverHistory() {
            when(rideRepository.findByDriverIdOrderByCreatedAtDesc(2L))
                    .thenReturn(List.of(ride));

            List<RideResponseDto> history = rideService.getDriverHistory(2L);

            assertEquals(1, history.size());
        }
    }
}
