package com.ridesharing.rating.controller;

import com.ridesharing.rating.dto.RatingRequest;
import com.ridesharing.rating.dto.RatingResponse;
import com.ridesharing.rating.service.RatingService;
import com.ridesharing.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ratings")
public class RatingController {

    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RatingResponse>> submitRating(
            Authentication auth,
            @Valid @RequestBody RatingRequest request) {
        Long userId = Long.parseLong(auth.getName());
        RatingResponse response = ratingService.submitRating(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Rating submitted successfully", response));
    }

    @GetMapping("/received")
    public ResponseEntity<ApiResponse<List<RatingResponse>>> getRatingsReceived(Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        List<RatingResponse> ratings = ratingService.getRatingsReceived(userId);
        return ResponseEntity.ok(ApiResponse.success("Ratings received", ratings));
    }

    @GetMapping("/given")
    public ResponseEntity<ApiResponse<List<RatingResponse>>> getRatingsGiven(Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        List<RatingResponse> ratings = ratingService.getRatingsGiven(userId);
        return ResponseEntity.ok(ApiResponse.success("Ratings given", ratings));
    }

    @GetMapping("/average/{userId}")
    public ResponseEntity<ApiResponse<Double>> getAverageRating(@PathVariable Long userId) {
        double avg = ratingService.getAverageRating(userId);
        return ResponseEntity.ok(ApiResponse.success("Average rating retrieved", avg));
    }
}
