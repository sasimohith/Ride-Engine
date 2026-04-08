package com.ridesharing.shared.enums;

// Tracks the lifecycle of a ride.
// Valid transitions:
//   REQUESTED → ACCEPTED → IN_PROGRESS → COMPLETED
//   REQUESTED → CANCELLED  (no driver found, or rider cancels)
//   ACCEPTED  → CANCELLED  (driver or rider cancels after acceptance)
public enum RideStatus {
    REQUESTED,
    ACCEPTED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
