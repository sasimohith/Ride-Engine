package com.ridesharing.payment.service;

import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.payment.dto.PaymentResponse;
import com.ridesharing.payment.model.Payment;
import com.ridesharing.payment.repository.PaymentRepository;
import com.ridesharing.ride.model.Ride;
import com.ridesharing.ride.repository.RideRepository;
import com.ridesharing.shared.enums.PaymentStatus;
import com.ridesharing.shared.enums.RideStatus;
import com.ridesharing.shared.exceptions.BadRequestException;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages the payment lifecycle:
 *   1. createPaymentForRide() — called by Kafka consumer when ride completes
 *   2. processPayment()       — called by rider to pay (CASH/UPI/WALLET)
 *   3. refundPayment()        — called by admin for disputes
 *   4. getPaymentHistory()    — ride/driver payment history
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final RideRepository rideRepository;
    private final UserRepository userRepository;

    public PaymentService(PaymentRepository paymentRepository,
                          RideRepository rideRepository,
                          UserRepository userRepository) {
        this.paymentRepository = paymentRepository;
        this.rideRepository = rideRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a PENDING payment record for a completed ride.
     * Called by the Kafka consumer when ride-completed event arrives.
     * Idempotent: skips if payment already exists for this ride.
     */
    public Payment createPaymentForRide(Long rideId, Long riderId, Long driverId, BigDecimal amount) {
        if (paymentRepository.existsByRideId(rideId)) {
            log.warn("Payment already exists for ride {} — skipping duplicate", rideId);
            return paymentRepository.findByRideId(rideId).orElse(null);
        }

        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride", "id", rideId));
        User rider = userRepository.findById(riderId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", riderId));
        User driver = userRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", driverId));

        Payment payment = Payment.builder()
                .ride(ride)
                .rider(rider)
                .driver(driver)
                .amount(amount)
                .status(PaymentStatus.PENDING)
                .build();

        payment = paymentRepository.save(payment);
        log.info("Payment {} created for ride {} — amount: ₹{}", payment.getId(), rideId, amount);
        return payment;
    }

    /**
     * Rider pays for the ride. Transitions PENDING → COMPLETED.
     * In a real system, this would call Razorpay/Stripe. We simulate it.
     */
    public PaymentResponse processPayment(Long riderId, Long paymentId, String paymentMethod) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", paymentId));

        if (!payment.getRider().getId().equals(riderId)) {
            throw new BadRequestException("You can only pay for your own rides");
        }

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new BadRequestException("Payment is not in PENDING state. Current: " + payment.getStatus());
        }

        payment.setPaymentMethod(paymentMethod.toUpperCase());
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setPaidAt(LocalDateTime.now());
        payment = paymentRepository.save(payment);

        log.info("Payment {} completed via {} — ride: {}, amount: ₹{}",
                payment.getId(), paymentMethod, payment.getRide().getId(), payment.getAmount());

        return toResponse(payment);
    }

    /**
     * Admin refunds a payment. Transitions COMPLETED → REFUNDED.
     */
    public PaymentResponse refundPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", paymentId));

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new BadRequestException("Only completed payments can be refunded");
        }

        payment.setStatus(PaymentStatus.REFUNDED);
        payment = paymentRepository.save(payment);

        log.info("Payment {} refunded — ride: {}, amount: ₹{}",
                payment.getId(), payment.getRide().getId(), payment.getAmount());

        return toResponse(payment);
    }

    public PaymentResponse getPaymentByRide(Long userId, Long rideId) {
        Payment payment = paymentRepository.findByRideId(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "rideId", rideId));

        boolean isParticipant = payment.getRider().getId().equals(userId)
                || payment.getDriver().getId().equals(userId);
        if (!isParticipant) {
            throw new BadRequestException("You are not part of this ride");
        }

        return toResponse(payment);
    }

    public List<PaymentResponse> getRiderPaymentHistory(Long riderId) {
        return paymentRepository.findByRiderIdOrderByCreatedAtDesc(riderId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<PaymentResponse> getDriverEarnings(Long driverId) {
        return paymentRepository.findByDriverIdOrderByCreatedAtDesc(driverId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private PaymentResponse toResponse(Payment p) {
        return PaymentResponse.builder()
                .paymentId(p.getId())
                .rideId(p.getRide().getId())
                .riderId(p.getRider().getId())
                .riderName(p.getRider().getName())
                .driverId(p.getDriver().getId())
                .driverName(p.getDriver().getName())
                .amount(p.getAmount())
                .paymentMethod(p.getPaymentMethod())
                .status(p.getStatus())
                .paidAt(p.getPaidAt())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
