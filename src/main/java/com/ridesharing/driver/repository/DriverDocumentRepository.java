package com.ridesharing.driver.repository;

import com.ridesharing.driver.model.DriverDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Database access for DriverDocument entity.
 * Spring auto-generates: findByDriverId → SELECT * FROM driver_documents WHERE driver_id = ?
 */
@Repository
public interface DriverDocumentRepository extends JpaRepository<DriverDocument, Long> {

    /**
     * Finds all documents uploaded by a specific driver.
     * Returns a List because a driver can have multiple documents (ManyToOne).
     */
    List<DriverDocument> findByDriverId(Long driverId);
}
