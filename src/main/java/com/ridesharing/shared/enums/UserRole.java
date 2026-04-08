package com.ridesharing.shared.enums;

// Defines the three roles in our system.
// Used by Auth module for role-based access control.
// Spring Security uses these to decide: "Is this user allowed to do X?"
public enum UserRole {
    RIDER,
    DRIVER,
    ADMIN
}
