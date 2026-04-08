-- =============================================================
-- V5: Create fare_rules table
-- Stores pricing configuration per vehicle type.
-- Each row = "how much does a SEDAN/AUTO/BIKE ride cost?"
-- =============================================================

CREATE TABLE fare_rules (
    id              BIGSERIAL       PRIMARY KEY,
    vehicle_type    VARCHAR(30)     NOT NULL UNIQUE,
    base_fare       DECIMAL(10, 2)  NOT NULL,
    per_km_rate     DECIMAL(10, 2)  NOT NULL,
    per_minute_rate DECIMAL(10, 2)  NOT NULL,
    minimum_fare    DECIMAL(10, 2)  NOT NULL,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Seed initial fare rules for common vehicle types
INSERT INTO fare_rules (vehicle_type, base_fare, per_km_rate, per_minute_rate, minimum_fare) VALUES
    ('AUTO',    25.00,  12.00, 1.50, 30.00),
    ('BIKE',    15.00,   8.00, 1.00, 20.00),
    ('SEDAN',   50.00,  15.00, 2.00, 60.00),
    ('SUV',     80.00,  20.00, 3.00, 100.00);

CREATE INDEX idx_fare_rules_vehicle_type ON fare_rules(vehicle_type);
