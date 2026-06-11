package com.ridesharing.rating.service;

import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.rating.dto.RatingRequest;
import com.ridesharing.rating.dto.RatingResponse;
import com.ridesharing.rating.model.Rating;
import com.ridesharing.rating.repository.RatingRepository;
import com.ridesharing.ride.model.Ride;
import com.ridesharing.ride.repository.RideRepository;
import com.ridesharing.shared.enums.RideStatus;
import com.ridesharing.shared.enums.UserRole;
import com.ridesharing.shared.exceptions.BadRequestException;
import com.ridesharing.shared.exceptions.ConflictException;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages ride ratings:
 *   - Rider rates Driver (after ride completes)
 *   - Driver rates Rider (after ride completes)
 *   - Calculates and updates average rating on User entity
 *
 * Business rules:
 *   1. Can only rate COMPLETED rides
 *   2. Can only rate once per ride per rater
 *   3. Can only rate someone you rode with (rider↔driver)
 *   4. Score must be 1-5
 */
@Service
@Transactional
public class RatingService {

    private static final Logger log = LoggerFactory.getLogger(RatingService.class);

    private final RatingRepository ratingRepository;
    private final RideRepository rideRepository;
    private final UserRepository userRepository;

    public RatingService(RatingRepository ratingRepository,
                         RideRepository rideRepository,
                         UserRepository userRepository) {
        this.ratingRepository = ratingRepository;
        this.rideRepository = rideRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public RatingResponse submitRating(Long raterId, RatingRequest request) {
        Ride ride = rideRepository.findById(request.getRideId())
                .orElseThrow(() -> new ResourceNotFoundException("Ride", "id", request.getRideId()));

        if (ride.getStatus() != RideStatus.COMPLETED) {
            throw new BadRequestException("Can only rate completed rides");
        }

        User rater = userRepository.findById(raterId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", raterId));

        User ratee = determineRatee(ride, rater);

        if (ratingRepository.existsByRideIdAndRaterId(ride.getId(), raterId)) {
            throw new ConflictException("You have already rated this ride");
        }

        Rating rating = Rating.builder()
                .ride(ride)
                .rater(rater)
                .ratee(ratee)
                .score(request.getScore())
                .comment(request.getComment())
                .build();

        rating = ratingRepository.save(rating);

        updateAverageRating(ratee);

        log.info("Rating submitted: rater={} rated ratee={} with score={} for ride={}",
                raterId, ratee.getId(), request.getScore(), ride.getId());

        return toResponse(rating);
    }

    public List<RatingResponse> getRatingsReceived(Long userId) {
        return ratingRepository.findByRateeIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<RatingResponse> getRatingsGiven(Long userId) {
        return ratingRepository.findByRaterIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public double getAverageRating(Long userId) {
        return ratingRepository.findAverageScoreByRateeId(userId).orElse(0.0);
    }

    /**
     * Determines who the ratee (person being rated) is:
     *   - If rater is the RIDER → ratee is the DRIVER
     *   - If rater is the DRIVER → ratee is the RIDER
     */
    private User determineRatee(Ride ride, User rater) {
        boolean isRider = ride.getRider().getId().equals(rater.getId());
        boolean isDriver = ride.getDriver() != null && ride.getDriver().getId().equals(rater.getId());

        if (!isRider && !isDriver) {
            throw new BadRequestException("You are not part of this ride");
        }

        if (isRider) {
            if (ride.getDriver() == null) {
                throw new BadRequestException("No driver assigned to this ride");
            }
            return ride.getDriver();
        } else {
            return ride.getRider();
        }
    }

    /**
     * Recalculates and denormalizes the average rating onto the User entity.
     * This avoids expensive AVG queries on every profile view.
     */
    private void updateAverageRating(User ratee) {
        Double avg = ratingRepository.findAverageScoreByRateeId(ratee.getId()).orElse(0.0);
        long count = ratingRepository.countByRateeId(ratee.getId());

        ratee.setAverageRating(BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP));
        ratee.setTotalRatings((int) count);
        userRepository.save(ratee);

        log.debug("Updated average rating for user {}: {} ({} ratings)",
                ratee.getId(), avg, count);
    }

    private RatingResponse toResponse(Rating r) {
        return RatingResponse.builder()
                .ratingId(r.getId())
                .rideId(r.getRide().getId())
                .raterId(r.getRater().getId())
                .raterName(r.getRater().getName())
                .rateeId(r.getRatee().getId())
                .rateeName(r.getRatee().getName())
                .score(r.getScore())
                .comment(r.getComment())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
