package com.ridesharing.ride.controller;

import com.ridesharing.ride.dto.RideRequestDto;
import com.ridesharing.ride.dto.RideResponseDto;
import com.ridesharing.ride.service.RideService;
import com.ridesharing.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rides")
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    /**
     * POST /api/rides/request
     * Rider requests a new ride. Triggers fare estimation + async driver matching.
     */
    @PostMapping("/request")
    public ResponseEntity<ApiResponse<RideResponseDto>> requestRide(
            Authentication auth,
            @Valid @RequestBody RideRequestDto request) {

        Long riderId = Long.parseLong(auth.getName());
        RideResponseDto ride = rideService.requestRide(riderId, request);
        return ResponseEntity.ok(ApiResponse.success("Ride requested — searching for drivers", ride));
    }

    /**
     * PUT /api/rides/{rideId}/start
     * Driver starts the ride (picks up the rider).
     */
    @PutMapping("/{rideId}/start")
    public ResponseEntity<ApiResponse<RideResponseDto>> startRide(
            Authentication auth,
            @PathVariable Long rideId) {

        Long driverId = Long.parseLong(auth.getName());
        RideResponseDto ride = rideService.startRide(driverId, rideId);
        return ResponseEntity.ok(ApiResponse.success("Ride started", ride));
    }

    /**
     * PUT /api/rides/{rideId}/complete
     * Driver completes the ride (reached destination).
     */
    @PutMapping("/{rideId}/complete")
    public ResponseEntity<ApiResponse<RideResponseDto>> completeRide(
            Authentication auth,
            @PathVariable Long rideId) {

        Long driverId = Long.parseLong(auth.getName());
        RideResponseDto ride = rideService.completeRide(driverId, rideId);
        return ResponseEntity.ok(ApiResponse.success("Ride completed", ride));
    }

    /**
     * PUT /api/rides/{rideId}/cancel
     * Either rider or driver cancels the ride.
     */
    @PutMapping("/{rideId}/cancel")
    public ResponseEntity<ApiResponse<RideResponseDto>> cancelRide(
            Authentication auth,
            @PathVariable Long rideId) {

        Long userId = Long.parseLong(auth.getName());
        RideResponseDto ride = rideService.cancelRide(userId, rideId);
        return ResponseEntity.ok(ApiResponse.success("Ride cancelled", ride));
    }

    /**
     * GET /api/rides/{rideId}
     * Get ride details (rider or driver).
     */
    @GetMapping("/{rideId}")
    public ResponseEntity<ApiResponse<RideResponseDto>> getRide(
            Authentication auth,
            @PathVariable Long rideId) {

        Long userId = Long.parseLong(auth.getName());
        RideResponseDto ride = rideService.getRide(userId, rideId);
        return ResponseEntity.ok(ApiResponse.success("Ride details retrieved", ride));
    }

    /**
     * GET /api/rides/history/rider
     * Get all past rides for the current rider.
     */
    @GetMapping("/history/rider")
    public ResponseEntity<ApiResponse<List<RideResponseDto>>> getRiderHistory(Authentication auth) {
        Long riderId = Long.parseLong(auth.getName());
        List<RideResponseDto> rides = rideService.getRiderHistory(riderId);
        return ResponseEntity.ok(ApiResponse.success("Rider history retrieved", rides));
    }

    /**
     * GET /api/rides/history/driver
     * Get all past rides for the current driver.
     */
    @GetMapping("/history/driver")
    public ResponseEntity<ApiResponse<List<RideResponseDto>>> getDriverHistory(Authentication auth) {
        Long driverId = Long.parseLong(auth.getName());
        List<RideResponseDto> rides = rideService.getDriverHistory(driverId);
        return ResponseEntity.ok(ApiResponse.success("Driver history retrieved", rides));
    }
}
