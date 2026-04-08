-- V3: Create the driver_documents table
-- A driver can have multiple documents (license, ID proof, insurance, RC book).
-- Admin reviews these before approving a driver.

CREATE TABLE driver_documents (
    id              BIGSERIAL       PRIMARY KEY,
    driver_id       BIGINT          NOT NULL REFERENCES users(id),
    document_type   VARCHAR(50)     NOT NULL,
    document_url    VARCHAR(500)    NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index on driver_id: find all documents for a driver quickly
CREATE INDEX idx_driver_documents_driver_id ON driver_documents(driver_id);
