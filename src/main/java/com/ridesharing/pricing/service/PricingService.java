package com.ridesharing.pricing.service;

import com.ridesharing.pricing.dto.FareEstimateRequest;
import com.ridesharing.pricing.dto.FareEstimateResponse;
import com.ridesharing.pricing.dto.FareRuleResponse;
import com.ridesharing.pricing.model.FareRule;
import com.ridesharing.pricing.repository.FareRuleRepository;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Core pricing logic: calculates ride fares using fare rules + surge multiplier.
 *
 * Uses the Redis Cache-Aside pattern for fare rules:
 *   1. Check Redis for cached fare rule
 *   2. Cache HIT  → return cached data (fast path, < 1ms)
 *   3. Cache MISS → query PostgreSQL → store in Redis with 1-hour TTL → return
 *
 * Surge multiplier is stored in Redis by the SurgePricingScheduler.
 * Default surge = 1.0 (no surge). During high demand, it can go up to 3.0.
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private static final String FARE_RULE_CACHE_PREFIX = "pricing:fare-rule:";
    private static final String SURGE_KEY_PREFIX = "pricing:surge:";
    private static final long FARE_RULE_CACHE_TTL_HOURS = 1;

    // Average speed assumption for time estimation (km/h)
    private static final double AVERAGE_SPEED_KMH = 25.0;

    private final FareRuleRepository fareRuleRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    public PricingService(FareRuleRepository fareRuleRepository,
                          RedisTemplate<String, Object> redisTemplate) {
        this.fareRuleRepository = fareRuleRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Calculates fare estimate for a ride request.
     *
     * Steps:
     *   1. Calculate distance between pickup and dropoff (Haversine formula)
     *   2. Estimate travel time from distance and average speed
     *   3. Fetch fare rule for the vehicle type (Redis cache-aside)
     *   4. Fetch current surge multiplier from Redis
     *   5. Apply formula: (baseFare + distanceCharge + timeCharge) * surge
     *   6. Ensure fare >= minimumFare
     */
    public FareEstimateResponse estimateFare(FareEstimateRequest request) {
        double distanceInKm = calculateDistance(
                request.getPickupLatitude(), request.getPickupLongitude(),
                request.getDropoffLatitude(), request.getDropoffLongitude()
        );

        double estimatedTimeMinutes = (distanceInKm / AVERAGE_SPEED_KMH) * 60;

        String vehicleType = request.getVehicleType().toUpperCase();
        FareRule fareRule = getFareRuleWithCache(vehicleType);

        BigDecimal surgeMultiplier = getCurrentSurge(vehicleType);

        BigDecimal distanceCharge = fareRule.getPerKmRate()
                .multiply(BigDecimal.valueOf(distanceInKm))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal timeCharge = fareRule.getPerMinuteRate()
                .multiply(BigDecimal.valueOf(estimatedTimeMinutes))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal rawFare = fareRule.getBaseFare()
                .add(distanceCharge)
                .add(timeCharge)
                .multiply(surgeMultiplier)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal estimatedFare = rawFare.max(fareRule.getMinimumFare());

        log.info("Fare estimate: vehicleType={}, distance={}km, time={}min, surge={}, fare={}",
                vehicleType, String.format("%.2f", distanceInKm),
                String.format("%.1f", estimatedTimeMinutes), surgeMultiplier, estimatedFare);

        return FareEstimateResponse.builder()
                .vehicleType(vehicleType)
                .distanceInKm(Math.round(distanceInKm * 100.0) / 100.0)
                .estimatedTimeInMinutes(Math.round(estimatedTimeMinutes * 10.0) / 10.0)
                .baseFare(fareRule.getBaseFare())
                .distanceCharge(distanceCharge)
                .timeCharge(timeCharge)
                .surgeMultiplier(surgeMultiplier)
                .estimatedFare(estimatedFare)
                .minimumFare(fareRule.getMinimumFare())
                .surgeActive(surgeMultiplier.compareTo(BigDecimal.ONE) > 0)
                .build();
    }

    /**
     * Redis Cache-Aside: fetch fare rule from cache, fallback to DB on miss.
     */
    FareRule getFareRuleWithCache(String vehicleType) {
        String cacheKey = FARE_RULE_CACHE_PREFIX + vehicleType;

        FareRule cached = (FareRule) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Cache HIT for fare rule: {}", vehicleType);
            return cached;
        }

        log.debug("Cache MISS for fare rule: {} — querying database", vehicleType);
        FareRule fareRule = fareRuleRepository.findByVehicleTypeAndActiveTrue(vehicleType)
                .orElseThrow(() -> new ResourceNotFoundException("FareRule", "vehicleType", vehicleType));

        redisTemplate.opsForValue().set(cacheKey, fareRule, FARE_RULE_CACHE_TTL_HOURS, TimeUnit.HOURS);

        return fareRule;
    }

    /**
     * Gets the current surge multiplier for a vehicle type from Redis.
     * Returns 1.0 (no surge) if no surge value is set.
     */
    BigDecimal getCurrentSurge(String vehicleType) {
        String surgeKey = SURGE_KEY_PREFIX + vehicleType;
        Object surgeValue = redisTemplate.opsForValue().get(surgeKey);

        if (surgeValue == null) {
            return BigDecimal.ONE;
        }

        if (surgeValue instanceof Number) {
            return BigDecimal.valueOf(((Number) surgeValue).doubleValue())
                    .setScale(1, RoundingMode.HALF_UP);
        }

        return BigDecimal.ONE;
    }

    /**
     * Returns all active fare rules (for admin dashboard or rider fare card).
     */
    public List<FareRuleResponse> getAllActiveFareRules() {
        return fareRuleRepository.findAllByActiveTrue().stream()
                .map(rule -> FareRuleResponse.builder()
                        .id(rule.getId())
                        .vehicleType(rule.getVehicleType())
                        .baseFare(rule.getBaseFare())
                        .perKmRate(rule.getPerKmRate())
                        .perMinuteRate(rule.getPerMinuteRate())
                        .minimumFare(rule.getMinimumFare())
                        .active(rule.isActive())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Invalidates the cached fare rule so the next request fetches fresh data from DB.
     * Called by admin when fare rules are updated.
     */
    public void invalidateFareRuleCache(String vehicleType) {
        String cacheKey = FARE_RULE_CACHE_PREFIX + vehicleType.toUpperCase();
        redisTemplate.delete(cacheKey);
        log.info("Invalidated fare rule cache for: {}", vehicleType);
    }

    /**
     * Haversine formula: calculates distance between two lat/lng points on Earth.
     *
     * Used instead of a mapping API to keep things simple and free.
     * Real-world apps use Google Maps Distance Matrix API for road distance.
     */
    static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final double EARTH_RADIUS_KM = 6371.0;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }
}
