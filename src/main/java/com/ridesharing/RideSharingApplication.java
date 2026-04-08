package com.ridesharing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// Main entry point for the Ride-Sharing Platform.
// @EnableAsync: activates async method execution (used by Notification, Matching modules)
// @EnableScheduling: activates scheduled tasks (used by SurgePricingScheduler)
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class RideSharingApplication {

    public static void main(String[] args) {
        SpringApplication.run(RideSharingApplication.class, args);
    }
}
