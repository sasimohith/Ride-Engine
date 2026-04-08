package com.ridesharing.pricing.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SurgePricingSchedulerTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private SetOperations<String, Object> setOperations;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private SurgePricingScheduler scheduler;

    @Nested
    @DisplayName("Surge Multiplier Calculation")
    class SurgeMultiplierTests {

        @Test
        @DisplayName("No surge when demand <= supply (ratio <= 1.0)")
        void noSurgeWhenBalanced() {
            assertEquals(1.0, scheduler.calculateSurgeMultiplier(0.5));
            assertEquals(1.0, scheduler.calculateSurgeMultiplier(1.0));
        }

        @Test
        @DisplayName("1.2x surge when ratio 1.0-1.5")
        void lightSurge() {
            assertEquals(1.2, scheduler.calculateSurgeMultiplier(1.1));
            assertEquals(1.2, scheduler.calculateSurgeMultiplier(1.5));
        }

        @Test
        @DisplayName("1.5x surge when ratio 1.5-2.0")
        void moderateSurge() {
            assertEquals(1.5, scheduler.calculateSurgeMultiplier(1.6));
            assertEquals(1.5, scheduler.calculateSurgeMultiplier(2.0));
        }

        @Test
        @DisplayName("2.0x surge when ratio 2.0-3.0")
        void highSurge() {
            assertEquals(2.0, scheduler.calculateSurgeMultiplier(2.5));
            assertEquals(2.0, scheduler.calculateSurgeMultiplier(3.0));
        }

        @Test
        @DisplayName("2.5x max surge cap when ratio > 3.0")
        void maxSurgeCap() {
            assertEquals(2.5, scheduler.calculateSurgeMultiplier(5.0));
            assertEquals(2.5, scheduler.calculateSurgeMultiplier(10.0));
        }
    }

    @Nested
    @DisplayName("Scheduled Surge Calculation")
    class ScheduledSurgeTests {

        @Test
        @DisplayName("Should set surge for all vehicle types based on demand/supply")
        void shouldSetSurgeBasedOnRatio() {
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            // 6 pending requests, 4 online drivers → ratio = 1.5 → surge = 1.2x
            when(setOperations.size("ride:pending-requests")).thenReturn(6L);
            when(zSetOperations.zCard("driver:locations")).thenReturn(4L);

            scheduler.calculateSurge();

            verify(valueOperations).set(eq("pricing:surge:AUTO"), eq(1.2), eq(120L), eq(TimeUnit.SECONDS));
            verify(valueOperations).set(eq("pricing:surge:BIKE"), eq(1.2), eq(120L), eq(TimeUnit.SECONDS));
            verify(valueOperations).set(eq("pricing:surge:SEDAN"), eq(1.2), eq(120L), eq(TimeUnit.SECONDS));
            verify(valueOperations).set(eq("pricing:surge:SUV"), eq(1.2), eq(120L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Should set max surge when no drivers are online")
        void shouldSetMaxSurgeWhenNoDrivers() {
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            when(setOperations.size("ride:pending-requests")).thenReturn(5L);
            when(zSetOperations.zCard("driver:locations")).thenReturn(0L);

            scheduler.calculateSurge();

            verify(valueOperations).set(eq("pricing:surge:AUTO"), eq(2.5), eq(120L), eq(TimeUnit.SECONDS));
            verify(valueOperations).set(eq("pricing:surge:BIKE"), eq(2.5), eq(120L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Should set no surge when supply exceeds demand")
        void shouldSetNoSurgeWhenSupplyExceedsDemand() {
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            when(setOperations.size("ride:pending-requests")).thenReturn(2L);
            when(zSetOperations.zCard("driver:locations")).thenReturn(10L);

            scheduler.calculateSurge();

            verify(valueOperations).set(eq("pricing:surge:AUTO"), eq(1.0), eq(120L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Should handle exceptions gracefully without crashing")
        void shouldHandleExceptionsGracefully() {
            when(redisTemplate.opsForSet()).thenThrow(new RuntimeException("Redis connection failed"));

            // Should not throw — scheduler must be resilient
            assertDoesNotThrow(() -> scheduler.calculateSurge());
        }
    }
}
