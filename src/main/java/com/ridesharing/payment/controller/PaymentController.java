package com.ridesharing.payment.controller;

import com.ridesharing.payment.dto.PayRequest;
import com.ridesharing.payment.dto.PaymentResponse;
import com.ridesharing.payment.service.PaymentService;
import com.ridesharing.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PutMapping("/{paymentId}/pay")
    public ResponseEntity<ApiResponse<PaymentResponse>> processPayment(
            Authentication auth,
            @PathVariable Long paymentId,
            @Valid @RequestBody PayRequest request) {
        Long userId = Long.parseLong(auth.getName());
        PaymentResponse response = paymentService.processPayment(userId, paymentId, request.getPaymentMethod());
        return ResponseEntity.ok(ApiResponse.success("Payment processed successfully", response));
    }

    @GetMapping("/ride/{rideId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPaymentByRide(
            Authentication auth,
            @PathVariable Long rideId) {
        Long userId = Long.parseLong(auth.getName());
        PaymentResponse response = paymentService.getPaymentByRide(userId, rideId);
        return ResponseEntity.ok(ApiResponse.success("Payment retrieved", response));
    }

    @GetMapping("/history/rider")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getRiderHistory(Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        List<PaymentResponse> history = paymentService.getRiderPaymentHistory(userId);
        return ResponseEntity.ok(ApiResponse.success("Payment history retrieved", history));
    }

    @GetMapping("/history/driver")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getDriverEarnings(Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        List<PaymentResponse> earnings = paymentService.getDriverEarnings(userId);
        return ResponseEntity.ok(ApiResponse.success("Driver earnings retrieved", earnings));
    }

    @PutMapping("/{paymentId}/refund")
    public ResponseEntity<ApiResponse<PaymentResponse>> refundPayment(
            @PathVariable Long paymentId) {
        PaymentResponse response = paymentService.refundPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.success("Payment refunded", response));
    }
}
