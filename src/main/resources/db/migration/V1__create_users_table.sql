-- V1: Create the users table
-- This is the FIRST migration. Flyway runs it once, then marks it as applied.
-- The "users" table stores all users: riders, drivers, and admins.
-- Role-specific data (vehicle, documents) will be in separate tables linked by user_id.

CREATE TABLE users (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(100)    NOT NULL,
    email       VARCHAR(255)    NOT NULL UNIQUE,
    password    VARCHAR(255)    NOT NULL,
    phone       VARCHAR(20)     NOT NULL,
    role        VARCHAR(20)     NOT NULL,
    active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index on email: speeds up login queries (SELECT WHERE email = ?)
-- Without this index, PostgreSQL scans EVERY row to find the email. With it: instant lookup.
CREATE INDEX idx_users_email ON users(email);

-- Index on role: speeds up queries like "find all pending drivers"
CREATE INDEX idx_users_role ON users(role);
