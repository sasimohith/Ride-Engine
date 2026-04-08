package com.ridesharing.payment.consumer;

import com.ridesharing.payment.service.PaymentService;
import com.ridesharing.ride.events.RideEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentEventConsumer Tests")
class PaymentEventConsumerTest {

    @Mock private PaymentService paymentService;
    @InjectMocks private PaymentEventConsumer consumer;

    @Test
    @DisplayName("Creates payment when ride-completed event received")
    void onRideCompleted_createsPayment() {
        RideEvent event = RideEvent.builder()
                .rideId(100L).riderId(1L).driverId(2L)
                .actualFare(new BigDecimal("105.00"))
                .estimatedFare(new BigDecimal("105.00"))
                .status("COMPLETED").build();

        consumer.onRideCompleted(event);

        verify(paymentService).createPaymentForRide(100L, 1L, 2L, new BigDecimal("105.00"));
    }

    @Test
    @DisplayName("Falls back to estimated fare if actual is null")
    void onRideCompleted_usesEstimatedFare() {
        RideEvent event = RideEvent.builder()
                .rideId(100L).riderId(1L).driverId(2L)
                .actualFare(null)
                .estimatedFare(new BigDecimal("100.00"))
                .status("COMPLETED").build();

        consumer.onRideCompleted(event);

        verify(paymentService).createPaymentForRide(100L, 1L, 2L, new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("Skips payment creation if both fares are null")
    void onRideCompleted_noFare_skips() {
        RideEvent event = RideEvent.builder()
                .rideId(100L).riderId(1L).driverId(2L)
                .actualFare(null).estimatedFare(null)
                .status("COMPLETED").build();

        consumer.onRideCompleted(event);

        verify(paymentService, never()).createPaymentForRide(any(), any(), any(), any());
    }
}
