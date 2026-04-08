package com.ridesharing.shared.enums;

// Tracks whether a driver is currently available for new rides.
// ONLINE  = driver is active and accepting ride requests
// OFFLINE = driver is not working (app closed or toggled off)
// BUSY    = driver is currently on an active ride
public enum DriverAvailability {
    ONLINE,
    OFFLINE,
    BUSY
}
