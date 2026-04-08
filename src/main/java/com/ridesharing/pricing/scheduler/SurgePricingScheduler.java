package com.ridesharing.pricing.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs on a schedule to calculate and update surge pricing multipliers.
 *
 * WHAT: Surge pricing increases fares when demand > supply in an area.
 * WHY:  Encourages more drivers to come online (higher earnings).
 *       Also discourages low-priority rides during peak times.
 * HOW:  Every 30 seconds, check demand (pending ride requests) vs
 *       supply (online drivers). If ratio > threshold, set surge > 1.0.
 *
 * Surge multiplier is stored in Redis with a 2-minute TTL.
 * If the scheduler stops, surge automatically expires (safety net).
 *
 * Surge brackets:
 *   demand/supply <= 1.0  → 1.0x (no surge)
 *   demand/supply <= 1.5  → 1.2x
 *   demand/supply <= 2.0  → 1.5x
 *   demand/supply <= 3.0  → 2.0x
 *   demand/supply >  3.0  → 2.5x (cap — never charge more than 2.5x)
 */
@Component
public class SurgePricingScheduler {

    private static final Logger log = LoggerFactory.getLogger(SurgePricingScheduler.class);

    private static final String SURGE_KEY_PREFIX = "pricing:surge:";
    private static final String RIDE_REQUESTS_KEY = "ride:pending-requests";
    private static final String DRIVER_LOCATIONS_KEY = "driver:locations";
    private static final long SURGE_TTL_SECONDS = 120;

    private final RedisTemplate<String, Object> redisTemplate;

    public SurgePricingScheduler(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Runs every 30 seconds. Recalculates surge multiplier based on
     * the ratio of pending ride requests to available online drivers.
     */
    @Scheduled(fixedRate = 30000)
    public void calculateSurge() {
        try {
            long pendingRequests = countPendingRequests();
            long onlineDrivers = countOnlineDrivers();

            if (onlineDrivers == 0) {
                // No drivers online — set max surge to attract drivers
                updateSurgeForAllTypes(2.5);
                log.warn("No online drivers. Setting max surge = 2.5x");
                return;
            }

            double ratio = (double) pendingRequests / onlineDrivers;
            double surge = calculateSurgeMultiplier(ratio);

            updateSurgeForAllTypes(surge);

            log.info("Surge calculated: requests={}, drivers={}, ratio={}, surge={}x",
                    pendingRequests, onlineDrivers,
                    String.format("%.2f", ratio), surge);

        } catch (Exception e) {
            log.error("Failed to calculate surge pricing", e);
        }
    }

    /**
     * Maps demand/supply ratio to a surge multiplier using defined brackets.
     */
    double calculateSurgeMultiplier(double ratio) {
        if (ratio <= 1.0) return 1.0;
        if (ratio <= 1.5) return 1.2;
        if (ratio <= 2.0) return 1.5;
        if (ratio <= 3.0) return 2.0;
        return 2.5;
    }

    private long countPendingRequests() {
        Long size = redisTemplate.opsForSet().size(RIDE_REQUESTS_KEY);
        return size != null ? size : 0;
    }

    private long countOnlineDrivers() {
        Long size = redisTemplate.opsForZSet().zCard(DRIVER_LOCATIONS_KEY);
        return size != null ? size : 0;
    }

    private void updateSurgeForAllTypes(double surge) {
        String[] vehicleTypes = {"AUTO", "BIKE", "SEDAN", "SUV"};
        for (String type : vehicleTypes) {
            String key = SURGE_KEY_PREFIX + type;
            redisTemplate.opsForValue().set(key, surge, SURGE_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }
}
