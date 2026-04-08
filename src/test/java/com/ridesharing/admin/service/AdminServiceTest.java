package com.ridesharing.admin.service;

import com.ridesharing.admin.dto.AdminDriverResponse;
import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.driver.model.DriverDocument;
import com.ridesharing.driver.model.Vehicle;
import com.ridesharing.driver.repository.DriverDocumentRepository;
import com.ridesharing.driver.repository.VehicleRepository;
import com.ridesharing.shared.enums.DriverApprovalStatus;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private DriverDocumentRepository driverDocumentRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private AdminService adminService;

    private User pendingDriver;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        pendingDriver = User.builder()
                .id(1L)
                .name("Ravi Kumar")
                .email("ravi@example.com")
                .phone("9876543210")
                .role(UserRole.DRIVER)
                .active(true)
                .approvalStatus(DriverApprovalStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        vehicle = Vehicle.builder()
                .id(1L)
                .driver(pendingDriver)
                .vehicleType("AUTO")
                .plateNumber("TN 01 AB 1234")
                .model("Bajaj RE")
                .color("Yellow")
                .build();
    }

    @Nested
    @DisplayName("Get Pending Drivers")
    class GetPendingDriversTests {

        @Test
        @DisplayName("Should return list of pending drivers with vehicle and document info")
        void shouldReturnPendingDrivers() {
            when(userRepository.findByRoleAndApprovalStatus(UserRole.DRIVER, DriverApprovalStatus.PENDING))
                    .thenReturn(List.of(pendingDriver));
            when(vehicleRepository.findByDriverId(1L)).thenReturn(Optional.of(vehicle));
            when(driverDocumentRepository.findByDriverId(1L)).thenReturn(List.of());

            List<AdminDriverResponse> result = adminService.getPendingDrivers();

            assertEquals(1, result.size());
            assertEquals("Ravi Kumar", result.get(0).getName());
            assertEquals(DriverApprovalStatus.PENDING, result.get(0).getApprovalStatus());
            assertEquals("AUTO", result.get(0).getVehicleType());
        }

        @Test
        @DisplayName("Should return empty list when no pending drivers")
        void shouldReturnEmptyWhenNoPendingDrivers() {
            when(userRepository.findByRoleAndApprovalStatus(UserRole.DRIVER, DriverApprovalStatus.PENDING))
                    .thenReturn(List.of());

            List<AdminDriverResponse> result = adminService.getPendingDrivers();

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Approve Driver")
    class ApproveDriverTests {

        @Test
        @DisplayName("Should approve a pending driver and publish Kafka event")
        void shouldApprovePendingDriver() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(pendingDriver));
            when(userRepository.save(any(User.class))).thenReturn(pendingDriver);
            when(vehicleRepository.findByDriverId(1L)).thenReturn(Optional.of(vehicle));
            when(driverDocumentRepository.findByDriverId(1L)).thenReturn(List.of());

            AdminDriverResponse result = adminService.approveDriver(1L);

            assertEquals(DriverApprovalStatus.APPROVED, pendingDriver.getApprovalStatus());
            verify(userRepository).save(pendingDriver);
            verify(kafkaTemplate).send(eq(KafkaTopics.DRIVER_APPROVED), eq("1"), any());
        }

        @Test
        @DisplayName("Should throw when driver is already approved")
        void shouldThrowWhenAlreadyApproved() {
            pendingDriver.setApprovalStatus(DriverApprovalStatus.APPROVED);
            when(userRepository.findById(1L)).thenReturn(Optional.of(pendingDriver));

            assertThrows(BadRequestException.class,
                    () -> adminService.approveDriver(1L));

            verify(userRepository, never()).save(any());
            verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Should throw when driver ID not found")
        void shouldThrowWhenDriverNotFound() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> adminService.approveDriver(999L));
        }

        @Test
        @DisplayName("Should throw when user is not a driver")
        void shouldThrowWhenUserIsNotDriver() {
            User rider = User.builder()
                    .id(2L)
                    .name("Test Rider")
                    .role(UserRole.RIDER)
                    .build();

            when(userRepository.findById(2L)).thenReturn(Optional.of(rider));

            assertThrows(BadRequestException.class,
                    () -> adminService.approveDriver(2L));
        }
    }

    @Nested
    @DisplayName("Reject Driver")
    class RejectDriverTests {

        @Test
        @DisplayName("Should reject a pending driver, deactivate, and publish Kafka event")
        void shouldRejectPendingDriver() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(pendingDriver));
            when(userRepository.save(any(User.class))).thenReturn(pendingDriver);
            when(vehicleRepository.findByDriverId(1L)).thenReturn(Optional.of(vehicle));
            when(driverDocumentRepository.findByDriverId(1L)).thenReturn(List.of());

            AdminDriverResponse result = adminService.rejectDriver(1L);

            assertEquals(DriverApprovalStatus.REJECTED, pendingDriver.getApprovalStatus());
            assertFalse(pendingDriver.isActive());
            verify(userRepository).save(pendingDriver);
            verify(kafkaTemplate).send(eq(KafkaTopics.DRIVER_APPROVED), eq("1"), any());
        }

        @Test
        @DisplayName("Should throw when driver is already rejected")
        void shouldThrowWhenAlreadyRejected() {
            pendingDriver.setApprovalStatus(DriverApprovalStatus.REJECTED);
            when(userRepository.findById(1L)).thenReturn(Optional.of(pendingDriver));

            assertThrows(BadRequestException.class,
                    () -> adminService.rejectDriver(1L));
        }
    }

    @Nested
    @DisplayName("Toggle User Active")
    class ToggleUserActiveTests {

        @Test
        @DisplayName("Should suspend a user")
        void shouldSuspendUser() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(pendingDriver));
            when(userRepository.save(any(User.class))).thenReturn(pendingDriver);

            adminService.toggleUserActive(1L, false);

            assertFalse(pendingDriver.isActive());
            verify(userRepository).save(pendingDriver);
        }

        @Test
        @DisplayName("Should reactivate a user")
        void shouldReactivateUser() {
            pendingDriver.setActive(false);
            when(userRepository.findById(1L)).thenReturn(Optional.of(pendingDriver));
            when(userRepository.save(any(User.class))).thenReturn(pendingDriver);

            adminService.toggleUserActive(1L, true);

            assertTrue(pendingDriver.isActive());
            verify(userRepository).save(pendingDriver);
        }

        @Test
        @DisplayName("Should throw when user not found")
        void shouldThrowWhenUserNotFound() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> adminService.toggleUserActive(999L, false));
        }
    }

    @Nested
    @DisplayName("Get Driver Details")
    class GetDriverDetailsTests {

        @Test
        @DisplayName("Should return full driver details including documents")
        void shouldReturnFullDriverDetails() {
            DriverDocument doc = DriverDocument.builder()
                    .id(1L)
                    .driver(pendingDriver)
                    .documentType("LICENSE")
                    .documentUrl("https://storage.example.com/license.pdf")
                    .build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(pendingDriver));
            when(vehicleRepository.findByDriverId(1L)).thenReturn(Optional.of(vehicle));
            when(driverDocumentRepository.findByDriverId(1L)).thenReturn(List.of(doc));

            AdminDriverResponse result = adminService.getDriverDetails(1L);

            assertEquals("Ravi Kumar", result.getName());
            assertEquals("AUTO", result.getVehicleType());
            assertEquals(1, result.getDocuments().size());
            assertEquals("LICENSE", result.getDocuments().get(0).getDocumentType());
        }
    }
}
