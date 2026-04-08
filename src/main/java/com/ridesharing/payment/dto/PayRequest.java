package com.ridesharing.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayRequest {

    @NotBlank(message = "Payment method is required")
    @Pattern(regexp = "CASH|UPI|WALLET", message = "Payment method must be CASH, UPI, or WALLET")
    private String paymentMethod;
}
