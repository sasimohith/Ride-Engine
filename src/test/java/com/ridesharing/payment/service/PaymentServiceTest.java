package com.ridesharing.payment.service;

import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.payment.dto.PaymentResponse;
import com.ridesharing.payment.model.Payment;
import com.ridesharing.payment.repository.PaymentRepository;
import com.ridesharing.ride.model.Ride;
import com.ridesharing.ride.repository.RideRepository;
import com.ridesharing.shared.enums.*;
import com.ridesharing.shared.exceptions.BadRequestException;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService Tests")
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RideRepository rideRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private PaymentService paymentService;

    private User rider;
    private User driver;
    private Ride ride;
    private Payment payment;

    @BeforeEach
    void setUp() {
        rider = User.builder().id(1L).name("Arun").email("arun@test.com")
                .role(UserRole.RIDER).active(true).build();
        driver = User.builder().id(2L).name("Ravi").email("ravi@test.com")
                .role(UserRole.DRIVER).active(true).build();
        ride = Ride.builder().id(100L).rider(rider).driver(driver)
                .status(RideStatus.COMPLETED).actualFare(new BigDecimal("105.00"))
                .estimatedFare(new BigDecimal("105.00")).build();
        payment = Payment.builder().id(1L).ride(ride).rider(rider).driver(driver)
                .amount(new BigDecimal("105.00")).status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();
    }

    @Nested
    @DisplayName("Create Payment For Ride")
    class CreatePaymentTests {

        @Test
        @DisplayName("Creates PENDING payment for completed ride")
        void createPayment_success() {
            when(paymentRepository.existsByRideId(100L)).thenReturn(false);
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
            when(userRepository.findById(1L)).thenReturn(Optional.of(rider));
            when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
                Payment p = inv.getArgument(0);
                p.setId(1L);
                return p;
            });

            Payment result = paymentService.createPaymentForRide(100L, 1L, 2L, new BigDecimal("105.00"));

            assertNotNull(result);
            assertEquals(PaymentStatus.PENDING, result.getStatus());
            assertEquals(new BigDecimal("105.00"), result.getAmount());
            verify(paymentRepository).save(any(Payment.class));
        }

        @Test
        @DisplayName("Skips duplicate payment (idempotent)")
        void createPayment_duplicate_skipped() {
            when(paymentRepository.existsByRideId(100L)).thenReturn(true);
            when(paymentRepository.findByRideId(100L)).thenReturn(Optional.of(payment));

            Payment result = paymentService.createPaymentForRide(100L, 1L, 2L, new BigDecimal("105.00"));

            assertNotNull(result);
            verify(paymentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Process Payment")
    class ProcessPaymentTests {

        @Test
        @DisplayName("Rider pays via UPI — PENDING → COMPLETED")
        void processPayment_success() {
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);

            PaymentResponse result = paymentService.processPayment(1L, 1L, "UPI");

            assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
            assertEquals("UPI", payment.getPaymentMethod());
            assertNotNull(payment.getPaidAt());
        }

        @Test
        @DisplayName("Non-rider cannot pay for the ride")
        void processPayment_notRider_fails() {
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            assertThrows(BadRequestException.class,
                    () -> paymentService.processPayment(999L, 1L, "CASH"));
        }

        @Test
        @DisplayName("Cannot pay already-completed payment")
        void processPayment_alreadyCompleted_fails() {
            payment.setStatus(PaymentStatus.COMPLETED);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            assertThrows(BadRequestException.class,
                    () -> paymentService.processPayment(1L, 1L, "CASH"));
        }

        @Test
        @DisplayName("Payment not found throws exception")
        void processPayment_notFound() {
            when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> paymentService.processPayment(1L, 99L, "CASH"));
        }
    }

    @Nested
    @DisplayName("Refund Payment")
    class RefundPaymentTests {

        @Test
        @DisplayName("Admin refunds completed payment")
        void refundPayment_success() {
            payment.setStatus(PaymentStatus.COMPLETED);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);

            PaymentResponse result = paymentService.refundPayment(1L);

            assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        }

        @Test
        @DisplayName("Cannot refund pending payment")
        void refundPayment_pending_fails() {
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            assertThrows(BadRequestException.class,
                    () -> paymentService.refundPayment(1L));
        }
    }

    @Nested
    @DisplayName("Payment History")
    class HistoryTests {

        @Test
        @DisplayName("Rider sees payment history")
        void riderHistory() {
            when(paymentRepository.findByRiderIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(payment));

            List<PaymentResponse> history = paymentService.getRiderPaymentHistory(1L);

            assertEquals(1, history.size());
            assertEquals(new BigDecimal("105.00"), history.get(0).getAmount());
        }

        @Test
        @DisplayName("Driver sees earnings history")
        void driverEarnings() {
            when(paymentRepository.findByDriverIdOrderByCreatedAtDesc(2L)).thenReturn(List.of(payment));

            List<PaymentResponse> earnings = paymentService.getDriverEarnings(2L);

            assertEquals(1, earnings.size());
        }

        @Test
        @DisplayName("Get payment by ride ID")
        void getByRide() {
            when(paymentRepository.findByRideId(100L)).thenReturn(Optional.of(payment));

            PaymentResponse result = paymentService.getPaymentByRide(1L, 100L);

            assertEquals(100L, result.getRideId());
        }

        @Test
        @DisplayName("Non-participant blocked from viewing payment")
        void getByRide_unauthorized() {
            when(paymentRepository.findByRideId(100L)).thenReturn(Optional.of(payment));

            assertThrows(BadRequestException.class,
                    () -> paymentService.getPaymentByRide(999L, 100L));
        }
    }
}
