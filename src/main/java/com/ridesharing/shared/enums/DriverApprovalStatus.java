package com.ridesharing.shared.enums;

// Tracks driver verification by admin.
// PENDING  = driver registered, waiting for admin to review documents
// APPROVED = admin verified documents, driver can go online
// REJECTED = admin rejected documents (invalid license, etc.)
public enum DriverApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
