package com.ridesharing.shared.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reusable DTO for any geographic location in the system.
 * Used by:
 *   - Ride module: pickup location, drop-off location
 *   - Driver module: driver's live GPS position
 *   - Matching: "find drivers within X km of this point"
 *
 * Latitude  = how far north/south (range: -90 to +90)
 * Longitude = how far east/west  (range: -180 to +180)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationDto {

    @NotNull(message = "Latitude is required")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    private Double longitude;
}
