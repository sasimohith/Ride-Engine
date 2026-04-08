package com.ridesharing.driver.dto;

import com.ridesharing.shared.enums.DriverApprovalStatus;
import com.ridesharing.shared.enums.DriverAvailability;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What we return when someone fetches a driver's profile.
 * Contains driver info + vehicle + approval status.
 * Does NOT include password or sensitive fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverProfileResponse {

    private Long driverId;
    private String name;
    private String email;
    private String phone;
    private DriverApprovalStatus approvalStatus;
    private DriverAvailability availability;
    private String vehicleType;
    private String plateNumber;
    private String vehicleModel;
    private String vehicleColor;
    private double averageRating;
}
