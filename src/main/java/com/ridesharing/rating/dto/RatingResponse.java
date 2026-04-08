package com.ridesharing.rating.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RatingResponse {

    private Long ratingId;
    private Long rideId;
    private Long raterId;
    private String raterName;
    private Long rateeId;
    private String rateeName;
    private int score;
    private String comment;
    private LocalDateTime createdAt;
}
