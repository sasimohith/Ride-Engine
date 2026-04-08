package com.ridesharing.notification.consumer;

import com.ridesharing.notification.service.NotificationService;
import com.ridesharing.ride.events.RideEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RideEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private RideEventConsumer rideEventConsumer;

    @Test
    @DisplayName("Should call notifyRiderDriverAccepted on RIDE_ACCEPTED event")
    void shouldHandleRideAcceptedEvent() {
        RideEvent event = RideEvent.builder()
                .rideId(100L).riderId(1L).driverId(10L)
                .status("ACCEPTED").build();

        rideEventConsumer.onRideAccepted(event);

        verify(notificationService).notifyRiderDriverAccepted(1L, 100L, 10L);
    }

    @Test
    @DisplayName("Should call notifyRideCompleted with actual fare on RIDE_COMPLETED event")
    void shouldHandleRideCompletedEvent() {
        RideEvent event = RideEvent.builder()
                .rideId(100L).riderId(1L).driverId(10L)
                .status("COMPLETED")
                .actualFare(new BigDecimal("110.50"))
                .estimatedFare(new BigDecimal("105.00"))
                .build();

        rideEventConsumer.onRideCompleted(event);

        verify(notificationService).notifyRideCompleted(1L, 10L, 100L, "110.50");
    }

    @Test
    @DisplayName("Should use estimated fare when actual fare is null")
    void shouldUseEstimatedFareWhenActualNull() {
        RideEvent event = RideEvent.builder()
                .rideId(100L).riderId(1L).driverId(10L)
                .status("COMPLETED")
                .actualFare(null)
                .estimatedFare(new BigDecimal("105.00"))
                .build();

        rideEventConsumer.onRideCompleted(event);

        verify(notificationService).notifyRideCompleted(1L, 10L, 100L, "105.00");
    }

    @Test
    @DisplayName("Should call notifyRideCancelled with 'no driver' reason when driverId is null")
    void shouldHandleRideCancelledNoDriver() {
        RideEvent event = RideEvent.builder()
                .rideId(100L).riderId(1L).driverId(null)
                .status("CANCELLED").build();

        rideEventConsumer.onRideCancelled(event);

        verify(notificationService).notifyRideCancelled(
                1L, null, 100L, "No driver was found for your ride. Please try again.");
    }

    @Test
    @DisplayName("Should call notifyRideCancelled with generic reason when driver was assigned")
    void shouldHandleRideCancelledWithDriver() {
        RideEvent event = RideEvent.builder()
                .rideId(100L).riderId(1L).driverId(10L)
                .status("CANCELLED").build();

        rideEventConsumer.onRideCancelled(event);

        verify(notificationService).notifyRideCancelled(
                1L, 10L, 100L, "Your ride has been cancelled.");
    }
}
