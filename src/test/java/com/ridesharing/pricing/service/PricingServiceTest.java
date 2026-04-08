package com.ridesharing.pricing.service;

import com.ridesharing.pricing.dto.FareEstimateRequest;
import com.ridesharing.pricing.dto.FareEstimateResponse;
import com.ridesharing.pricing.dto.FareRuleResponse;
import com.ridesharing.pricing.model.FareRule;
import com.ridesharing.pricing.repository.FareRuleRepository;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    private FareRuleRepository fareRuleRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private PricingService pricingService;

    private FareRule autoFareRule;

    @BeforeEach
    void setUp() {
        autoFareRule = FareRule.builder()
                .id(1L)
                .vehicleType("AUTO")
                .baseFare(new BigDecimal("25.00"))
                .perKmRate(new BigDecimal("12.00"))
                .perMinuteRate(new BigDecimal("1.50"))
                .minimumFare(new BigDecimal("30.00"))
                .active(true)
                .build();
    }

    @Nested
    @DisplayName("Haversine Distance Calculation")
    class DistanceCalculationTests {

        @Test
        @DisplayName("Should calculate correct distance between two known points")
        void shouldCalculateCorrectDistance() {
            // Bangalore MG Road to Koramangala (~4.5 km)
            double distance = PricingService.calculateDistance(
                    12.9716, 77.5946,  // MG Road
                    12.9352, 77.6245   // Koramangala
            );

            assertTrue(distance > 4.0 && distance < 6.0,
                    "Distance should be approximately 4-6 km, got: " + distance);
        }

        @Test
        @DisplayName("Should return zero for same coordinates")
        void shouldReturnZeroForSamePoint() {
            double distance = PricingService.calculateDistance(
                    12.9716, 77.5946,
                    12.9716, 77.5946
            );

            assertEquals(0.0, distance, 0.001);
        }

        @Test
        @DisplayName("Should handle long distances")
        void shouldHandleLongDistances() {
            // Bangalore to Chennai (~290 km)
            double distance = PricingService.calculateDistance(
                    12.9716, 77.5946,  // Bangalore
                    13.0827, 80.2707   // Chennai
            );

            assertTrue(distance > 280 && distance < 310,
                    "Distance should be approximately 290 km, got: " + distance);
        }
    }

    @Nested
    @DisplayName("Fare Estimation")
    class FareEstimationTests {

        @Test
        @DisplayName("Should calculate fare with no surge")
        void shouldCalculateFareWithNoSurge() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("pricing:fare-rule:AUTO")).thenReturn(autoFareRule);
            when(valueOperations.get("pricing:surge:AUTO")).thenReturn(null);

            FareEstimateRequest request = FareEstimateRequest.builder()
                    .pickupLatitude(12.9716)
                    .pickupLongitude(77.5946)
                    .dropoffLatitude(12.9352)
                    .dropoffLongitude(77.6245)
                    .vehicleType("AUTO")
                    .build();

            FareEstimateResponse response = pricingService.estimateFare(request);

            assertNotNull(response);
            assertEquals("AUTO", response.getVehicleType());
            assertTrue(response.getDistanceInKm() > 0);
            assertTrue(response.getEstimatedTimeInMinutes() > 0);
            assertEquals(new BigDecimal("25.00"), response.getBaseFare());
            assertEquals(0, BigDecimal.ONE.compareTo(response.getSurgeMultiplier()));
            assertFalse(response.isSurgeActive());
            assertTrue(response.getEstimatedFare().compareTo(response.getMinimumFare()) >= 0);
        }

        @Test
        @DisplayName("Should apply surge multiplier correctly")
        void shouldApplySurgeMultiplier() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("pricing:fare-rule:AUTO")).thenReturn(autoFareRule);
            when(valueOperations.get("pricing:surge:AUTO")).thenReturn(2.0);

            FareEstimateRequest request = FareEstimateRequest.builder()
                    .pickupLatitude(12.9716)
                    .pickupLongitude(77.5946)
                    .dropoffLatitude(12.9352)
                    .dropoffLongitude(77.6245)
                    .vehicleType("auto")
                    .build();

            FareEstimateResponse response = pricingService.estimateFare(request);

            assertTrue(response.isSurgeActive());
            assertEquals(0, new BigDecimal("2.0").compareTo(response.getSurgeMultiplier()));
        }

        @Test
        @DisplayName("Should enforce minimum fare")
        void shouldEnforceMinimumFare() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("pricing:fare-rule:AUTO")).thenReturn(autoFareRule);
            when(valueOperations.get("pricing:surge:AUTO")).thenReturn(null);

            // Very short distance — calculated fare will be less than minimum
            FareEstimateRequest request = FareEstimateRequest.builder()
                    .pickupLatitude(12.9716)
                    .pickupLongitude(77.5946)
                    .dropoffLatitude(12.9717)
                    .dropoffLongitude(77.5947)
                    .vehicleType("AUTO")
                    .build();

            FareEstimateResponse response = pricingService.estimateFare(request);

            assertTrue(response.getEstimatedFare().compareTo(autoFareRule.getMinimumFare()) >= 0,
                    "Fare should be at least the minimum fare");
        }

        @Test
        @DisplayName("Should throw when vehicle type not found")
        void shouldThrowWhenVehicleTypeNotFound() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("pricing:fare-rule:HELICOPTER")).thenReturn(null);
            when(fareRuleRepository.findByVehicleTypeAndActiveTrue("HELICOPTER"))
                    .thenReturn(Optional.empty());

            FareEstimateRequest request = FareEstimateRequest.builder()
                    .pickupLatitude(12.9716)
                    .pickupLongitude(77.5946)
                    .dropoffLatitude(12.9352)
                    .dropoffLongitude(77.6245)
                    .vehicleType("HELICOPTER")
                    .build();

            assertThrows(ResourceNotFoundException.class,
                    () -> pricingService.estimateFare(request));
        }
    }

    @Nested
    @DisplayName("Cache-Aside Pattern")
    class CacheAsideTests {

        @Test
        @DisplayName("Should return from cache on HIT")
        void shouldReturnFromCacheOnHit() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("pricing:fare-rule:AUTO")).thenReturn(autoFareRule);

            FareRule result = pricingService.getFareRuleWithCache("AUTO");

            assertEquals(autoFareRule, result);
            verify(fareRuleRepository, never()).findByVehicleTypeAndActiveTrue(any());
        }

        @Test
        @DisplayName("Should query DB and populate cache on MISS")
        void shouldQueryDbOnCacheMiss() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("pricing:fare-rule:AUTO")).thenReturn(null);
            when(fareRuleRepository.findByVehicleTypeAndActiveTrue("AUTO"))
                    .thenReturn(Optional.of(autoFareRule));

            FareRule result = pricingService.getFareRuleWithCache("AUTO");

            assertEquals(autoFareRule, result);
            verify(fareRuleRepository).findByVehicleTypeAndActiveTrue("AUTO");
            verify(valueOperations).set(
                    eq("pricing:fare-rule:AUTO"),
                    eq(autoFareRule),
                    eq(1L),
                    eq(TimeUnit.HOURS)
            );
        }
    }

    @Nested
    @DisplayName("Surge Multiplier Retrieval")
    class SurgeRetrievalTests {

        @Test
        @DisplayName("Should return 1.0 when no surge is set")
        void shouldReturnOneWhenNoSurge() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("pricing:surge:AUTO")).thenReturn(null);

            BigDecimal surge = pricingService.getCurrentSurge("AUTO");

            assertEquals(0, BigDecimal.ONE.compareTo(surge));
        }

        @Test
        @DisplayName("Should return surge value from Redis")
        void shouldReturnSurgeFromRedis() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("pricing:surge:SEDAN")).thenReturn(1.5);

            BigDecimal surge = pricingService.getCurrentSurge("SEDAN");

            assertEquals(0, new BigDecimal("1.5").compareTo(surge));
        }
    }

    @Nested
    @DisplayName("Get All Active Fare Rules")
    class GetAllFareRulesTests {

        @Test
        @DisplayName("Should return all active fare rules as DTOs")
        void shouldReturnAllActiveFareRules() {
            FareRule bikeFareRule = FareRule.builder()
                    .id(2L)
                    .vehicleType("BIKE")
                    .baseFare(new BigDecimal("15.00"))
                    .perKmRate(new BigDecimal("8.00"))
                    .perMinuteRate(new BigDecimal("1.00"))
                    .minimumFare(new BigDecimal("20.00"))
                    .active(true)
                    .build();

            when(fareRuleRepository.findAllByActiveTrue())
                    .thenReturn(List.of(autoFareRule, bikeFareRule));

            List<FareRuleResponse> rules = pricingService.getAllActiveFareRules();

            assertEquals(2, rules.size());
            assertEquals("AUTO", rules.get(0).getVehicleType());
            assertEquals("BIKE", rules.get(1).getVehicleType());
        }
    }

    @Nested
    @DisplayName("Cache Invalidation")
    class CacheInvalidationTests {

        @Test
        @DisplayName("Should delete cache key for given vehicle type")
        void shouldDeleteCacheKey() {
            when(redisTemplate.delete("pricing:fare-rule:AUTO")).thenReturn(true);

            pricingService.invalidateFareRuleCache("auto");

            verify(redisTemplate).delete("pricing:fare-rule:AUTO");
        }
    }
}
