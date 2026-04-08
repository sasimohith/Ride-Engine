-- =============================================================
-- V8: Create payments table
-- Each completed ride has exactly ONE payment record.
-- Payment is created automatically via Kafka consumer when
-- the ride-completed event fires.
-- =============================================================

CREATE TABLE payments (
    id              BIGSERIAL PRIMARY KEY,
    ride_id         BIGINT NOT NULL REFERENCES rides(id),
    rider_id        BIGINT NOT NULL REFERENCES users(id),
    driver_id       BIGINT NOT NULL REFERENCES users(id),

    amount          DECIMAL(10,2) NOT NULL,
    payment_method  VARCHAR(30),                          -- CASH, UPI, WALLET
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',  -- PENDING, COMPLETED, FAILED, REFUNDED

    paid_at         TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_payments_ride UNIQUE(ride_id)           -- one payment per ride
);

CREATE INDEX idx_payments_rider ON payments(rider_id);
CREATE INDEX idx_payments_driver ON payments(driver_id);
CREATE INDEX idx_payments_status ON payments(status);
