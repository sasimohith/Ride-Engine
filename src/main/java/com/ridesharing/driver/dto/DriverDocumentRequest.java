package com.ridesharing.driver.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the client sends to POST /api/driver/documents.
 * Driver uploads document details (type + URL of uploaded file).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDocumentRequest {

    @NotBlank(message = "Document type is required (DRIVING_LICENSE, ID_PROOF, VEHICLE_INSURANCE, RC_BOOK)")
    private String documentType;

    @NotBlank(message = "Document URL is required")
    private String documentUrl;
}
