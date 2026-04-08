package com.ridesharing.driver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages driver locations using Redis GEO data structure.
 *
 * Redis GEO key: "driver:locations"
 *   - Stores ALL online drivers as members with lat/lng coordinates
 *   - Supports: add/update location, remove location, find within radius
 *   - Performance: < 1ms for radius queries even with 100,000+ drivers
 *
 * WHAT: Redis GEO stores geospatial data (latitude, longitude) and supports
 *       distance calculations and radius queries natively.
 * WHY:  PostgreSQL can do geo queries, but Redis does them in microseconds
 *       because everything is in memory. For real-time location tracking
 *       with updates every 5 seconds, this speed is essential.
 * HOW:  GEOADD adds/updates a member's coordinates.
 *       GEOSEARCH finds all members within a given radius.
 *       GEODEL removes a member (driver goes offline).
 */
@Service
public class DriverLocationService {

    private static final Logger log = LoggerFactory.getLogger(DriverLocationService.class);

    // Redis GEO key — one key stores ALL driver locations
    // Key naming: "driver:locations" — clear, descriptive, follows our convention
    private static final String DRIVER_LOCATIONS_KEY = "driver:locations";

    private final RedisTemplate<String, Object> redisTemplate;

    public DriverLocationService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Updates a driver's GPS location in Redis GEO.
     * Called every 5 seconds by the driver's app while they are ONLINE.
     *
     * Redis command: GEOADD driver:locations <longitude> <latitude> <driverId>
     *
     * If the driver already exists in the set, their position is updated.
     * If the driver is new, they are added to the set.
     *
     * @param driverId the driver's unique ID
     * @param latitude the driver's current latitude (e.g., 12.9716)
     * @param longitude the driver's current longitude (e.g., 77.5946)
     */
    public void updateDriverLocation(Long driverId, double latitude, double longitude) {
        // Redis GEO expects Point(longitude, latitude) — note the order!
        // This is a common gotcha: longitude comes FIRST in Redis, but humans think lat first
        redisTemplate.opsForGeo().add(
                DRIVER_LOCATIONS_KEY,
                new Point(longitude, latitude),
                driverId.toString()
        );

        log.debug("Updated location for driver {}: lat={}, lng={}", driverId, latitude, longitude);
    }

    /**
     * Removes a driver from the Redis GEO set.
     * Called when a driver goes OFFLINE or becomes BUSY (on a ride).
     *
     * Redis command: ZREM driver:locations <driverId>
     * (GEO is built on top of sorted sets, so ZREM removes the member)
     *
     * @param driverId the driver to remove from active locations
     */
    public void removeDriverLocation(Long driverId) {
        redisTemplate.opsForGeo().remove(DRIVER_LOCATIONS_KEY, driverId.toString());
        log.debug("Removed location for driver {}", driverId);
    }

    /**
     * Finds all drivers within a given radius of a point.
     * This is THE critical method — called when a rider requests a ride.
     *
     * Redis command: GEOSEARCH driver:locations FROMLONLAT <lng> <lat> BYRADIUS <km> km ASC COUNT 10
     *
     * Returns drivers sorted by distance (nearest first), limited to 10 results.
     * Each result includes the driverId and distance from the search point.
     *
     * @param latitude the center point latitude (rider's location)
     * @param longitude the center point longitude (rider's location)
     * @param radiusInKm the search radius in kilometers (e.g., 3.0)
     * @return list of [driverId, distanceInKm] pairs, sorted nearest first
     */
    public List<DriverLocationResult> findNearbyDrivers(double latitude, double longitude, double radiusInKm) {
        // Build the search circle: center point + radius
        Circle searchArea = new Circle(
                new Point(longitude, latitude),
                new Distance(radiusInKm, RedisGeoCommands.DistanceUnit.KILOMETERS)
        );

        // Execute the geo search with distance included in results
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs
                .newGeoRadiusArgs()
                .includeDistance()
                .sortAscending()
                .limit(10);

        GeoResults<RedisGeoCommands.GeoLocation<Object>> results =
                redisTemplate.opsForGeo().radius(DRIVER_LOCATIONS_KEY, searchArea, args);

        if (results == null) {
            log.debug("No drivers found within {} km of ({}, {})", radiusInKm, latitude, longitude);
            return Collections.emptyList();
        }

        List<DriverLocationResult> nearbyDrivers = new ArrayList<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<Object>> result : results) {
            String driverIdStr = result.getContent().getName().toString();
            double distance = result.getDistance().getValue();

            nearbyDrivers.add(new DriverLocationResult(
                    Long.parseLong(driverIdStr),
                    distance
            ));
        }

        log.info("Found {} drivers within {} km of ({}, {})",
                nearbyDrivers.size(), radiusInKm, latitude, longitude);

        return nearbyDrivers;
    }

    /**
     * Simple record to hold a nearby driver's ID and distance.
     * Used internally by findNearbyDrivers() to return structured results.
     */
    public record DriverLocationResult(Long driverId, double distanceInKm) {
    }
}
