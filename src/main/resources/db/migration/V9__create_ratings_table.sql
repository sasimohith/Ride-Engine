-- =============================================================
-- V9: Create ratings table
-- Both rider and driver can rate each other after ride completes.
-- One rating per rater per ride (enforced by unique constraint).
-- =============================================================

CREATE TABLE ratings (
    id              BIGSERIAL PRIMARY KEY,
    ride_id         BIGINT NOT NULL REFERENCES rides(id),
    rater_id        BIGINT NOT NULL REFERENCES users(id),   -- who gave the rating
    ratee_id        BIGINT NOT NULL REFERENCES users(id),   -- who received the rating
    score           INT NOT NULL CHECK (score >= 1 AND score <= 5),
    comment         VARCHAR(500),

    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_ratings_rater_ride UNIQUE(ride_id, rater_id)  -- one rating per person per ride
);

CREATE INDEX idx_ratings_ratee ON ratings(ratee_id);

-- Add average_rating column to users table for denormalized fast reads
ALTER TABLE users ADD COLUMN average_rating DECIMAL(3,2) DEFAULT 0.00;
ALTER TABLE users ADD COLUMN total_ratings INT DEFAULT 0;
