package com.ridesharing.driver.service;

import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.driver.dto.VehicleRequest;
import com.ridesharing.driver.model.Vehicle;
import com.ridesharing.driver.repository.DriverDocumentRepository;
import com.ridesharing.driver.repository.VehicleRepository;
import com.ridesharing.shared.enums.DriverApprovalStatus;
import com.ridesharing.shared.enums.DriverAvailability;
import com.ridesharing.shared.enums.UserRole;
import com.ridesharing.shared.exceptions.BadRequestException;
import com.ridesharing.shared.exceptions.ConflictException;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for DriverService — vehicle, documents, availability logic.
 *
 * Happy path: add vehicle, go online (approved driver)
 * Failure 1: add vehicle when user is not a DRIVER → BadRequestException
 * Failure 2: add vehicle when already has one → ConflictException
 * Failure 3: go online when not approved → BadRequestException
 * Edge case: go offline removes from Redis GEO
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DriverService Tests")
class DriverServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private DriverDocumentRepository driverDocumentRepository;
    @Mock
    private DriverLocationService driverLocationService;

    @InjectMocks
    private DriverService driverService;

    private User driverUser;
    private VehicleRequest vehicleRequest;

    @BeforeEach
    void setUp() {
        driverUser = User.builder()
                .id(1L)
                .name("Raj Driver")
                .email("raj@driver.com")
                .password("hashed")
                .phone("9876543210")
                .role(UserRole.DRIVER)
                .approvalStatus(DriverApprovalStatus.APPROVED)
                .availability(DriverAvailability.OFFLINE)
                .active(true)
                .build();

        vehicleRequest = VehicleRequest.builder()
                .vehicleType("AUTO")
                .plateNumber("TN 01 AB 1234")
                .model("Bajaj RE")
                .color("Green")
                .build();
    }

    // ── ADD VEHICLE TESTS ──

    @Test
    @DisplayName("should_addVehicle_when_driverHasNoVehicle")
    void should_addVehicle_when_driverHasNoVehicle() {
        // GIVEN
        when(userRepository.findById(1L)).thenReturn(Optional.of(driverUser));
        when(vehicleRepository.findByDriverId(1L)).thenReturn(Optional.empty());
        when(vehicleRepository.existsByPlateNumber("TN 01 AB 1234")).thenReturn(false);
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(inv -> inv.getArgument(0));

        // WHEN
        Vehicle result = driverService.addVehicle(1L, vehicleRequest);

        // THEN
        assertNotNull(result);
        verify(vehicleRepository).save(any(Vehicle.class));
    }

    @Test
    @DisplayName("should_throwConflictException_when_driverAlreadyHasVehicle")
    void should_throwConflictException_when_driverAlreadyHasVehicle() {
        // GIVEN: driver already has a vehicle
        when(userRepository.findById(1L)).thenReturn(Optional.of(driverUser));
        when(vehicleRepository.findByDriverId(1L)).thenReturn(Optional.of(new Vehicle()));

        // WHEN / THEN
        assertThrows(ConflictException.class,
                () -> driverService.addVehicle(1L, vehicleRequest));
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwBadRequestException_when_userIsNotDriver")
    void should_throwBadRequestException_when_userIsNotDriver() {
        // GIVEN: user is a RIDER, not a DRIVER
        driverUser.setRole(UserRole.RIDER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(driverUser));

        // WHEN / THEN
        assertThrows(BadRequestException.class,
                () -> driverService.addVehicle(1L, vehicleRequest));
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_driverNotFound")
    void should_throwResourceNotFoundException_when_driverNotFound() {
        // GIVEN: driver ID doesn't exist
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // WHEN / THEN
        assertThrows(ResourceNotFoundException.class,
                () -> driverService.addVehicle(999L, vehicleRequest));
    }

    // ── AVAILABILITY TESTS ──

    @Test
    @DisplayName("should_goOnline_when_driverIsApproved")
    void should_goOnline_when_driverIsApproved() {
        // GIVEN: approved driver
        when(userRepository.findById(1L)).thenReturn(Optional.of(driverUser));
        when(userRepository.save(any(User.class))).thenReturn(driverUser);

        // WHEN
        driverService.updateAvailability(1L, DriverAvailability.ONLINE, 12.97, 77.59);

        // THEN: saved to DB AND added to Redis GEO
        verify(userRepository).save(any(User.class));
        verify(driverLocationService).updateDriverLocation(1L, 12.97, 77.59);
    }

    @Test
    @DisplayName("should_throwBadRequestException_when_unapprovedDriverGoesOnline")
    void should_throwBadRequestException_when_unapprovedDriverGoesOnline() {
        // GIVEN: driver is PENDING approval
        driverUser.setApprovalStatus(DriverApprovalStatus.PENDING);
        when(userRepository.findById(1L)).thenReturn(Optional.of(driverUser));

        // WHEN / THEN
        assertThrows(BadRequestException.class,
                () -> driverService.updateAvailability(1L, DriverAvailability.ONLINE, 12.97, 77.59));
    }

    @Test
    @DisplayName("should_removeFromRedis_when_driverGoesOffline")
    void should_removeFromRedis_when_driverGoesOffline() {
        // GIVEN
        when(userRepository.findById(1L)).thenReturn(Optional.of(driverUser));
        when(userRepository.save(any(User.class))).thenReturn(driverUser);

        // WHEN
        driverService.updateAvailability(1L, DriverAvailability.OFFLINE, null, null);

        // THEN: removed from Redis GEO (not searchable by riders anymore)
        verify(driverLocationService).removeDriverLocation(1L);
    }
}
