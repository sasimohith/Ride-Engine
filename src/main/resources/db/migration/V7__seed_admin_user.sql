-- =============================================================
-- V7: Seed a default admin user for the platform.
-- Password is BCrypt hash of "admin123"
-- In production, change this immediately after first login.
-- =============================================================

INSERT INTO users (name, email, password, phone, role, active, created_at, updated_at)
VALUES (
    'Platform Admin',
    'admin@ridesharing.com',
    '$2a$10$Fdwvb0mdf5rLHNPHCJT08uHnzFZy9dkoQ6mc4STYWxeb.94j79246',
    '0000000000',
    'ADMIN',
    true,
    NOW(),
    NOW()
);
