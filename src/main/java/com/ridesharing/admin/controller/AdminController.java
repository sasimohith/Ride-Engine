package com.ridesharing.admin.controller;

import com.ridesharing.admin.dto.AdminDriverResponse;
import com.ridesharing.admin.service.AdminService;
import com.ridesharing.shared.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin-only REST endpoints.
 * All endpoints under /api/admin/** require ROLE_ADMIN (configured in SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * GET /api/admin/drivers/pending
     * Lists all drivers awaiting admin approval.
     */
    @GetMapping("/drivers/pending")
    public ResponseEntity<ApiResponse<List<AdminDriverResponse>>> getPendingDrivers() {
        List<AdminDriverResponse> drivers = adminService.getPendingDrivers();
        return ResponseEntity.ok(ApiResponse.success("Pending drivers retrieved", drivers));
    }

    /**
     * GET /api/admin/drivers
     * Lists all drivers in the system (any approval status).
     */
    @GetMapping("/drivers")
    public ResponseEntity<ApiResponse<List<AdminDriverResponse>>> getAllDrivers() {
        List<AdminDriverResponse> drivers = adminService.getAllDrivers();
        return ResponseEntity.ok(ApiResponse.success("All drivers retrieved", drivers));
    }

    /**
     * GET /api/admin/drivers/{driverId}
     * Returns full details of a specific driver for review.
     */
    @GetMapping("/drivers/{driverId}")
    public ResponseEntity<ApiResponse<AdminDriverResponse>> getDriverDetails(
            @PathVariable Long driverId) {
        AdminDriverResponse driver = adminService.getDriverDetails(driverId);
        return ResponseEntity.ok(ApiResponse.success("Driver details retrieved", driver));
    }

    /**
     * PUT /api/admin/drivers/{driverId}/approve
     * Approves a driver registration.
     */
    @PutMapping("/drivers/{driverId}/approve")
    public ResponseEntity<ApiResponse<AdminDriverResponse>> approveDriver(
            @PathVariable Long driverId) {
        AdminDriverResponse driver = adminService.approveDriver(driverId);
        return ResponseEntity.ok(ApiResponse.success("Driver approved successfully", driver));
    }

    /**
     * PUT /api/admin/drivers/{driverId}/reject
     * Rejects a driver registration.
     */
    @PutMapping("/drivers/{driverId}/reject")
    public ResponseEntity<ApiResponse<AdminDriverResponse>> rejectDriver(
            @PathVariable Long driverId) {
        AdminDriverResponse driver = adminService.rejectDriver(driverId);
        return ResponseEntity.ok(ApiResponse.success("Driver rejected", driver));
    }

    /**
     * PUT /api/admin/users/{userId}/suspend
     * Suspends a user account (sets active = false).
     */
    @PutMapping("/users/{userId}/suspend")
    public ResponseEntity<ApiResponse<Void>> suspendUser(@PathVariable Long userId) {
        adminService.toggleUserActive(userId, false);
        return ResponseEntity.ok(ApiResponse.success("User suspended"));
    }

    /**
     * PUT /api/admin/users/{userId}/reactivate
     * Reactivates a suspended user account.
     */
    @PutMapping("/users/{userId}/reactivate")
    public ResponseEntity<ApiResponse<Void>> reactivateUser(@PathVariable Long userId) {
        adminService.toggleUserActive(userId, true);
        return ResponseEntity.ok(ApiResponse.success("User reactivated"));
    }
}
