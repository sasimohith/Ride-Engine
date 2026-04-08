-- V4: Add driver-specific fields to the users table
-- These columns are only meaningful for users with role='DRIVER'.
-- Riders and Admins will have NULL for these fields.

ALTER TABLE users ADD COLUMN approval_status VARCHAR(20) DEFAULT NULL;
ALTER TABLE users ADD COLUMN availability VARCHAR(20) DEFAULT NULL;

-- Index on approval_status: admin queries "find all pending drivers"
CREATE INDEX idx_users_approval_status ON users(approval_status);
