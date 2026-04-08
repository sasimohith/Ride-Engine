package com.ridesharing.driver.model;

import com.ridesharing.auth.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The DriverDocument entity — maps to the "driver_documents" table.
 * A driver can upload MULTIPLE documents: license, ID proof, insurance, etc.
 *
 * @ManyToOne with User: many documents → one driver
 * (one driver can have 3-4 documents, but each document belongs to one driver)
 *
 * Admin reviews these documents to approve or reject the driver.
 * documentUrl stores the URL/path where the file is uploaded (e.g., S3 bucket URL).
 */
@Entity
@Table(name = "driver_documents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The driver who uploaded this document.
     * @ManyToOne = many documents belong to one driver.
     * @JoinColumn(name = "driver_id") = foreign key in this table.
     */
    @ManyToOne
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    /**
     * Type of document: "DRIVING_LICENSE", "ID_PROOF", "VEHICLE_INSURANCE", "RC_BOOK".
     */
    @Column(name = "document_type", nullable = false)
    private String documentType;

    /**
     * URL where the document file is stored.
     * In production: S3 bucket URL like "https://s3.amazonaws.com/ridesharing/docs/license_42.jpg"
     * In dev: could be a local file path.
     */
    @Column(name = "document_url", nullable = false)
    private String documentUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
