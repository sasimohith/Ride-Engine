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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Business logic for admin operations.
 *
 * Admin can:
 *   1. View all pending driver registrations
 *   2. Approve a driver (allows them to go online)
 *   3. Reject a driver (blocks them from the platform)
 *   4. View all drivers with any approval status
 *
 * On approve/reject, a Kafka event is published so the Notification Module
 * can inform the driver about their application status.
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverDocumentRepository driverDocumentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AdminService(UserRepository userRepository,
                        VehicleRepository vehicleRepository,
                        DriverDocumentRepository driverDocumentRepository,
                        KafkaTemplate<String, Object> kafkaTemplate) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverDocumentRepository = driverDocumentRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Returns all drivers with PENDING approval status.
     * These are drivers waiting for admin review.
     */
    public List<AdminDriverResponse> getPendingDrivers() {
        List<User> pendingDrivers = userRepository.findByRoleAndApprovalStatus(
                UserRole.DRIVER, DriverApprovalStatus.PENDING);

        log.info("Found {} pending driver registrations", pendingDrivers.size());
        return pendingDrivers.stream()
                .map(this::buildDriverResponse)
                .collect(Collectors.toList());
    }

    /**
     * Returns all drivers regardless of approval status.
     */
    public List<AdminDriverResponse> getAllDrivers() {
        List<User> drivers = userRepository.findByRole(UserRole.DRIVER);

        log.info("Found {} total drivers", drivers.size());
        return drivers.stream()
                .map(this::buildDriverResponse)
                .collect(Collectors.toList());
    }

    /**
     * Returns a single driver's full details for admin review.
     */
    public AdminDriverResponse getDriverDetails(Long driverId) {
        User driver = findDriverOrThrow(driverId);
        return buildDriverResponse(driver);
    }

    /**
     * Approves a driver registration.
     * After approval, the driver can set their availability to ONLINE and accept rides.
     * Publishes a Kafka event for the Notification Module.
     */
    public AdminDriverResponse approveDriver(Long driverId) {
        User driver = findDriverOrThrow(driverId);

        if (driver.getApprovalStatus() == DriverApprovalStatus.APPROVED) {
            throw new BadRequestException("Driver is already approved");
        }

        driver.setApprovalStatus(DriverApprovalStatus.APPROVED);
        userRepository.save(driver);

        publishDriverApprovalEvent(driverId, DriverApprovalStatus.APPROVED);

        log.info("Driver {} approved by admin", driverId);
        return buildDriverResponse(driver);
    }

    /**
     * Rejects a driver registration.
     * Rejected drivers cannot go online or accept rides.
     * Publishes a Kafka event for the Notification Module.
     */
    public AdminDriverResponse rejectDriver(Long driverId) {
        User driver = findDriverOrThrow(driverId);

        if (driver.getApprovalStatus() == DriverApprovalStatus.REJECTED) {
            throw new BadRequestException("Driver is already rejected");
        }

        driver.setApprovalStatus(DriverApprovalStatus.REJECTED);
        driver.setActive(false);
        userRepository.save(driver);

        publishDriverApprovalEvent(driverId, DriverApprovalStatus.REJECTED);

        log.info("Driver {} rejected by admin", driverId);
        return buildDriverResponse(driver);
    }

    /**
     * Toggles a user's active status (suspend / reactivate).
     */
    public void toggleUserActive(Long userId, boolean active) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        user.setActive(active);
        userRepository.save(user);

        log.info("User {} active status set to {}", userId, active);
    }

    private User findDriverOrThrow(Long driverId) {
        User user = userRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", "id", driverId));

        if (user.getRole() != UserRole.DRIVER) {
            throw new BadRequestException("User " + driverId + " is not a driver");
        }

        return user;
    }

    private AdminDriverResponse buildDriverResponse(User driver) {
        Vehicle vehicle = vehicleRepository.findByDriverId(driver.getId()).orElse(null);
        List<DriverDocument> documents = driverDocumentRepository.findByDriverId(driver.getId());

        List<AdminDriverResponse.DocumentInfo> docInfos = documents != null
                ? documents.stream()
                    .map(doc -> AdminDriverResponse.DocumentInfo.builder()
                            .id(doc.getId())
                            .documentType(doc.getDocumentType())
                            .documentUrl(doc.getDocumentUrl())
                            .build())
                    .collect(Collectors.toList())
                : Collections.emptyList();

        return AdminDriverResponse.builder()
                .driverId(driver.getId())
                .name(driver.getName())
                .email(driver.getEmail())
                .phone(driver.getPhone())
                .active(driver.isActive())
                .approvalStatus(driver.getApprovalStatus())
                .availability(driver.getAvailability())
                .registeredAt(driver.getCreatedAt())
                .vehicleType(vehicle != null ? vehicle.getVehicleType() : null)
                .plateNumber(vehicle != null ? vehicle.getPlateNumber() : null)
                .vehicleModel(vehicle != null ? vehicle.getModel() : null)
                .vehicleColor(vehicle != null ? vehicle.getColor() : null)
                .documents(docInfos)
                .build();
    }

    private void publishDriverApprovalEvent(Long driverId, DriverApprovalStatus status) {
        Map<String, Object> event = Map.of(
                "driverId", driverId,
                "status", status.name(),
                "timestamp", System.currentTimeMillis()
        );

        kafkaTemplate.send(KafkaTopics.DRIVER_APPROVED, driverId.toString(), event);
        log.info("Published driver approval event: driverId={}, status={}", driverId, status);
    }
}
