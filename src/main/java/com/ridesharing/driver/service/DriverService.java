package com.ridesharing.driver.service;

import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.driver.dto.DriverDocumentRequest;
import com.ridesharing.driver.dto.DriverProfileResponse;
import com.ridesharing.driver.dto.VehicleRequest;
import com.ridesharing.driver.model.DriverDocument;
import com.ridesharing.driver.model.Vehicle;
import com.ridesharing.driver.repository.DriverDocumentRepository;
import com.ridesharing.driver.repository.VehicleRepository;
import com.ridesharing.shared.enums.DriverApprovalStatus;
import com.ridesharing.shared.enums.DriverAvailability;
import com.ridesharing.shared.enums.UserRole;
import com.ridesharing.shared.exceptions.BadRequestException;
import com.ridesharing.shared.exceptions.ConflictException;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for all driver operations:
 *   - Add vehicle details
 *   - Upload documents
 *   - Toggle availability (online/offline)
 *   - Get driver profile
 *
 * Coordinates between: UserRepository, VehicleRepository,
 * DriverDocumentRepository, and DriverLocationService (Redis GEO).
 */
@Service
public class DriverService {

    private static final Logger log = LoggerFactory.getLogger(DriverService.class);

    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverDocumentRepository driverDocumentRepository;
    private final DriverLocationService driverLocationService;

    public DriverService(UserRepository userRepository,
                         VehicleRepository vehicleRepository,
                         DriverDocumentRepository driverDocumentRepository,
                         DriverLocationService driverLocationService) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverDocumentRepository = driverDocumentRepository;
        this.driverLocationService = driverLocationService;
    }

    /**
     * Adds vehicle details for a driver.
     * A driver can only have ONE vehicle. If they already have one, throw ConflictException.
     *
     * @param driverId the authenticated driver's user ID
     * @param request vehicle details from the client
     * @return the saved Vehicle entity
     * @throws ResourceNotFoundException if driver not found
     * @throws BadRequestException if user is not a DRIVER
     * @throws ConflictException if driver already has a vehicle or plate number taken
     */
    @Transactional
    public Vehicle addVehicle(Long driverId, VehicleRequest request) {
        log.info("Adding vehicle for driver {}", driverId);

        User driver = getVerifiedDriver(driverId);

        // Check if driver already has a vehicle
        if (vehicleRepository.findByDriverId(driverId).isPresent()) {
            throw new ConflictException("Driver already has a vehicle registered");
        }

        // Check if plate number is already taken by another driver
        if (vehicleRepository.existsByPlateNumber(request.getPlateNumber())) {
            throw new ConflictException("Plate number already registered: " + request.getPlateNumber());
        }

        Vehicle vehicle = Vehicle.builder()
                .driver(driver)
                .vehicleType(request.getVehicleType())
                .plateNumber(request.getPlateNumber())
                .model(request.getModel())
                .color(request.getColor())
                .build();

        Vehicle savedVehicle = vehicleRepository.save(vehicle);
        log.info("Vehicle added for driver {}: type={}, plate={}",
                driverId, request.getVehicleType(), request.getPlateNumber());

        return savedVehicle;
    }

    /**
     * Uploads a document for a driver.
     * A driver can have multiple documents (license, ID proof, insurance, etc.).
     *
     * @param driverId the authenticated driver's user ID
     * @param request document details from the client
     * @return the saved DriverDocument entity
     */
    @Transactional
    public DriverDocument addDocument(Long driverId, DriverDocumentRequest request) {
        log.info("Adding document for driver {}: type={}", driverId, request.getDocumentType());

        User driver = getVerifiedDriver(driverId);

        DriverDocument document = DriverDocument.builder()
                .driver(driver)
                .documentType(request.getDocumentType())
                .documentUrl(request.getDocumentUrl())
                .build();

        return driverDocumentRepository.save(document);
    }

    /**
     * Toggles a driver's availability: ONLINE, OFFLINE, or BUSY.
     * ONLINE: driver can receive ride requests. Also adds to Redis GEO.
     * OFFLINE: driver stops receiving rides. Also removes from Redis GEO.
     * BUSY: driver is on a ride. Also removes from Redis GEO.
     *
     * Only APPROVED drivers can go ONLINE.
     *
     * @param driverId the authenticated driver's user ID
     * @param availability the new availability status
     * @param latitude driver's current latitude (needed when going ONLINE)
     * @param longitude driver's current longitude (needed when going ONLINE)
     */
    @Transactional
    public void updateAvailability(Long driverId, DriverAvailability availability,
                                   Double latitude, Double longitude) {
        log.info("Updating availability for driver {}: {}", driverId, availability);

        User driver = getVerifiedDriver(driverId);

        // Only admin-approved drivers can go ONLINE
        if (availability == DriverAvailability.ONLINE
                && driver.getApprovalStatus() != DriverApprovalStatus.APPROVED) {
            throw new BadRequestException("Driver must be approved by admin before going online");
        }

        driver.setAvailability(availability);
        userRepository.save(driver);

        // Update Redis GEO based on new availability
        if (availability == DriverAvailability.ONLINE && latitude != null && longitude != null) {
            driverLocationService.updateDriverLocation(driverId, latitude, longitude);
        } else {
            // OFFLINE or BUSY: remove from nearby search results
            driverLocationService.removeDriverLocation(driverId);
        }

        log.info("Driver {} is now {}", driverId, availability);
    }

    /**
     * Fetches a driver's full profile: personal info + vehicle details.
     *
     * @param driverId the driver's user ID
     * @return DriverProfileResponse with all relevant info
     */
    public DriverProfileResponse getDriverProfile(Long driverId) {
        User driver = getVerifiedDriver(driverId);

        DriverProfileResponse.DriverProfileResponseBuilder responseBuilder = DriverProfileResponse.builder()
                .driverId(driver.getId())
                .name(driver.getName())
                .email(driver.getEmail())
                .phone(driver.getPhone())
                .approvalStatus(driver.getApprovalStatus())
                .availability(driver.getAvailability());

        // Attach vehicle details if the driver has a vehicle
        vehicleRepository.findByDriverId(driverId).ifPresent(vehicle -> {
            responseBuilder
                    .vehicleType(vehicle.getVehicleType())
                    .plateNumber(vehicle.getPlateNumber())
                    .vehicleModel(vehicle.getModel())
                    .vehicleColor(vehicle.getColor());
        });

        return responseBuilder.build();
    }

    /**
     * Helper: fetches a user by ID and verifies they have the DRIVER role.
     * Reused by multiple methods to avoid code duplication.
     *
     * @param driverId the user ID to look up
     * @return the verified User with DRIVER role
     * @throws ResourceNotFoundException if user not found
     * @throws BadRequestException if user is not a DRIVER
     */
    private User getVerifiedDriver(Long driverId) {
        User user = userRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", "id", driverId));

        if (user.getRole() != UserRole.DRIVER) {
            throw new BadRequestException("User with id " + driverId + " is not a driver");
        }

        return user;
    }
}
