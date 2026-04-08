package com.ridesharing.pricing.controller;

import com.ridesharing.pricing.dto.FareEstimateRequest;
import com.ridesharing.pricing.dto.FareEstimateResponse;
import com.ridesharing.pricing.dto.FareRuleResponse;
import com.ridesharing.pricing.service.PricingService;
import com.ridesharing.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pricing")
public class PricingController {

    private final PricingService pricingService;

    public PricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    /**
     * POST /api/pricing/estimate
     * Calculates fare estimate before rider confirms the ride.
     * Open to any authenticated user.
     */
    @PostMapping("/estimate")
    public ResponseEntity<ApiResponse<FareEstimateResponse>> estimateFare(
            @Valid @RequestBody FareEstimateRequest request) {

        FareEstimateResponse estimate = pricingService.estimateFare(request);
        return ResponseEntity.ok(ApiResponse.success("Fare estimated successfully", estimate));
    }

    /**
     * GET /api/pricing/fare-rules
     * Returns all active fare rules (fare card).
     * Useful for riders to see base pricing before requesting.
     */
    @GetMapping("/fare-rules")
    public ResponseEntity<ApiResponse<List<FareRuleResponse>>> getAllFareRules() {
        List<FareRuleResponse> rules = pricingService.getAllActiveFareRules();
        return ResponseEntity.ok(ApiResponse.success("Fare rules retrieved", rules));
    }

    /**
     * DELETE /api/pricing/cache/{vehicleType}
     * Admin endpoint to invalidate cached fare rule after updating pricing.
     */
    @DeleteMapping("/cache/{vehicleType}")
    public ResponseEntity<ApiResponse<Void>> invalidateCache(@PathVariable String vehicleType) {
        pricingService.invalidateFareRuleCache(vehicleType);
        return ResponseEntity.ok(ApiResponse.success("Cache invalidated for " + vehicleType));
    }
}
