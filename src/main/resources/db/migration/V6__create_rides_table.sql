-- =============================================================
-- V6: Create rides table
-- The core table — every ride request creates one row here.
-- Tracks the full lifecycle: REQUESTED → ACCEPTED → IN_PROGRESS → COMPLETED
-- =============================================================

CREATE TABLE rides (
    id                  BIGSERIAL       PRIMARY KEY,
    rider_id            BIGINT          NOT NULL REFERENCES users(id),
    driver_id           BIGINT          REFERENCES users(id),

    -- Pickup location
    pickup_latitude     DOUBLE PRECISION NOT NULL,
    pickup_longitude    DOUBLE PRECISION NOT NULL,
    pickup_address      VARCHAR(500),

    -- Dropoff location
    dropoff_latitude    DOUBLE PRECISION NOT NULL,
    dropoff_longitude   DOUBLE PRECISION NOT NULL,
    dropoff_address     VARCHAR(500),

    -- Ride details
    vehicle_type        VARCHAR(30)     NOT NULL,
    status              VARCHAR(30)     NOT NULL DEFAULT 'REQUESTED',
    distance_km         DOUBLE PRECISION,
    estimated_time_min  DOUBLE PRECISION,

    -- Fare details
    estimated_fare      DECIMAL(10, 2),
    actual_fare         DECIMAL(10, 2),
    surge_multiplier    DECIMAL(3, 1)   DEFAULT 1.0,

    -- Timestamps
    requested_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    accepted_at         TIMESTAMP,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    cancelled_at        TIMESTAMP,

    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Indexes for common queries
CREATE INDEX idx_rides_rider_id ON rides(rider_id);
CREATE INDEX idx_rides_driver_id ON rides(driver_id);
CREATE INDEX idx_rides_status ON rides(status);
CREATE INDEX idx_rides_requested_at ON rides(requested_at);
