package com.ridesharing.shared.enums;

// Tracks payment state after a ride is completed.
// PENDING  = payment record created, charge not yet processed
// COMPLETED = money successfully collected
// FAILED   = payment attempt failed (retry or manual intervention needed)
// REFUNDED = money returned to rider (e.g., dispute resolved in rider's favour)
public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED
}
