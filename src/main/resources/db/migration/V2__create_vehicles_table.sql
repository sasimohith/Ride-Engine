-- V2: Create the vehicles table
-- Each driver has exactly one vehicle. Links to users table via driver_id.
-- Used by: Ride module (show rider what vehicle is coming), Admin (verify vehicle details)

CREATE TABLE vehicles (
    id              BIGSERIAL       PRIMARY KEY,
    driver_id       BIGINT          NOT NULL UNIQUE REFERENCES users(id),
    vehicle_type    VARCHAR(20)     NOT NULL,
    plate_number    VARCHAR(20)     NOT NULL UNIQUE,
    model           VARCHAR(100)    NOT NULL,
    color           VARCHAR(30)     NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index on driver_id: find a driver's vehicle quickly
CREATE INDEX idx_vehicles_driver_id ON vehicles(driver_id);
