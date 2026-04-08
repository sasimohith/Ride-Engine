package com.ridesharing.admin.dto;

import com.ridesharing.shared.enums.DriverApprovalStatus;
import com.ridesharing.shared.enums.DriverAvailability;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin's view of a driver — includes everything an admin needs
 * to decide whether to approve or reject a driver registration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminDriverResponse {

    private Long driverId;
    private String name;
    private String email;
    private String phone;
    private boolean active;
    private DriverApprovalStatus approvalStatus;
    private DriverAvailability availability;
    private LocalDateTime registeredAt;

    private String vehicleType;
    private String plateNumber;
    private String vehicleModel;
    private String vehicleColor;

    private List<DocumentInfo> documents;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentInfo {
        private Long id;
        private String documentType;
        private String documentUrl;
    }
}
