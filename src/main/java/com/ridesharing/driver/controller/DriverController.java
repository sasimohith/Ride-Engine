package com.ridesharing.driver.controller;

import com.ridesharing.driver.dto.DriverDocumentRequest;
import com.ridesharing.driver.dto.DriverProfileResponse;
import com.ridesharing.driver.dto.VehicleRequest;
import com.ridesharing.driver.service.DriverLocationService;
import com.ridesharing.driver.service.DriverService;
import com.ridesharing.shared.dto.ApiResponse;
import com.ridesharing.shared.dto.LocationDto;
import com.ridesharing.shared.enums.DriverAvailability;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for driver operations.
 *
 * Endpoints:
 *   POST /api/driver/vehicle        — Add vehicle details
 *   POST /api/driver/documents      — Upload a document
 *   PUT  /api/driver/availability    — Go online/offline
 *   PUT  /api/driver/location        — Update live GPS location
 *   GET  /api/driver/profile         — Get own profile
 *
 * All endpoints require authentication (JWT token).
 * The driver's userId is extracted from the JWT via Authentication object.
 */
@RestController
@RequestMapping("/api/driver")
public class DriverController {

    private static final Logger log = LoggerFactory.getLogger(DriverController.class);

    private final DriverService driverService;
    private final DriverLocationService driverLocationService;

    public DriverController(DriverService driverService,
                            DriverLocationService driverLocationService) {
        this.driverService = driverService;
        this.driverLocationService = driverLocationService;
    }

    /**
     * Adds vehicle details for the authenticated driver.
     * The driverId is extracted from the JWT token (not from the URL — security!).
     */
    @PostMapping("/vehicle")
    public ResponseEntity<ApiResponse<Void>> addVehicle(
            @Valid @RequestBody VehicleRequest request,
            Authentication authentication) {

        Long driverId = (Long) authentication.getPrincipal();
        log.info("Add vehicle request from driver {}", driverId);

        driverService.addVehicle(driverId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Vehicle added successfully"));
    }

    /**
     * Uploads a document for the authenticated driver.
     */
    @PostMapping("/documents")
    public ResponseEntity<ApiResponse<Void>> addDocument(
            @Valid @RequestBody DriverDocumentRequest request,
            Authentication authentication) {

        Long driverId = (Long) authentication.getPrincipal();
        log.info("Add document request from driver {}: type={}", driverId, request.getDocumentType());

        driverService.addDocument(driverId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Document uploaded successfully"));
    }

    /**
     * Toggles driver availability: ONLINE, OFFLINE, or BUSY.
     * When going ONLINE, latitude and longitude are required (to add to Redis GEO).
     */
    @PutMapping("/availability")
    public ResponseEntity<ApiResponse<Void>> updateAvailability(
            @RequestParam DriverAvailability status,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            Authentication authentication) {

        Long driverId = (Long) authentication.getPrincipal();
        log.info("Availability update from driver {}: {}", driverId, status);

        driverService.updateAvailability(driverId, status, latitude, longitude);
        return ResponseEntity.ok(ApiResponse.success("Availability updated to " + status));
    }

    /**
     * Updates the driver's live GPS location in Redis.
     * Called every 5 seconds by the driver's app while ONLINE.
     */
    @PutMapping("/location")
    public ResponseEntity<ApiResponse<Void>> updateLocation(
            @Valid @RequestBody LocationDto location,
            Authentication authentication) {

        Long driverId = (Long) authentication.getPrincipal();

        driverLocationService.updateDriverLocation(
                driverId, location.getLatitude(), location.getLongitude());

        return ResponseEntity.ok(ApiResponse.success("Location updated"));
    }

    /**
     * Returns the authenticated driver's full profile.
     */
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<DriverProfileResponse>> getProfile(
            Authentication authentication) {

        Long driverId = (Long) authentication.getPrincipal();
        DriverProfileResponse profile = driverService.getDriverProfile(driverId);

        return ResponseEntity.ok(ApiResponse.success("Driver profile", profile));
    }
}
