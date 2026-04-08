package com.ridesharing.rating.service;

import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.rating.dto.RatingRequest;
import com.ridesharing.rating.dto.RatingResponse;
import com.ridesharing.rating.model.Rating;
import com.ridesharing.rating.repository.RatingRepository;
import com.ridesharing.ride.model.Ride;
import com.ridesharing.ride.repository.RideRepository;
import com.ridesharing.shared.enums.*;
import com.ridesharing.shared.exceptions.BadRequestException;
import com.ridesharing.shared.exceptions.ConflictException;
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
@DisplayName("RatingService Tests")
class RatingServiceTest {

    @Mock private RatingRepository ratingRepository;
    @Mock private RideRepository rideRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private RatingService ratingService;

    private User rider;
    private User driver;
    private Ride ride;

    @BeforeEach
    void setUp() {
        rider = User.builder().id(1L).name("Arun").email("arun@test.com")
                .role(UserRole.RIDER).active(true)
                .averageRating(BigDecimal.ZERO).totalRatings(0).build();
        driver = User.builder().id(2L).name("Ravi").email("ravi@test.com")
                .role(UserRole.DRIVER).active(true)
                .averageRating(BigDecimal.ZERO).totalRatings(0).build();
        ride = Ride.builder().id(100L).rider(rider).driver(driver)
                .status(RideStatus.COMPLETED).build();
    }

    @Nested
    @DisplayName("Submit Rating")
    class SubmitRatingTests {

        @Test
        @DisplayName("Rider rates driver successfully")
        void riderRatesDriver_success() {
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
            when(userRepository.findById(1L)).thenReturn(Optional.of(rider));
            when(ratingRepository.existsByRideIdAndRaterId(100L, 1L)).thenReturn(false);
            when(ratingRepository.save(any(Rating.class))).thenAnswer(inv -> {
                Rating r = inv.getArgument(0);
                r.setId(1L);
                r.setCreatedAt(LocalDateTime.now());
                return r;
            });
            when(ratingRepository.findAverageScoreByRateeId(2L)).thenReturn(Optional.of(4.5));
            when(ratingRepository.countByRateeId(2L)).thenReturn(1L);
            when(userRepository.save(any(User.class))).thenReturn(driver);

            RatingRequest request = RatingRequest.builder()
                    .rideId(100L).score(5).comment("Great ride!").build();

            RatingResponse response = ratingService.submitRating(1L, request);

            assertNotNull(response);
            assertEquals(5, response.getScore());
            assertEquals("Great ride!", response.getComment());
            assertEquals(1L, response.getRaterId());
            assertEquals(2L, response.getRateeId());
            verify(ratingRepository).save(any(Rating.class));
            verify(userRepository).save(driver);
        }

        @Test
        @DisplayName("Driver rates rider successfully")
        void driverRatesRider_success() {
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
            when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
            when(ratingRepository.existsByRideIdAndRaterId(100L, 2L)).thenReturn(false);
            when(ratingRepository.save(any(Rating.class))).thenAnswer(inv -> {
                Rating r = inv.getArgument(0);
                r.setId(2L);
                r.setCreatedAt(LocalDateTime.now());
                return r;
            });
            when(ratingRepository.findAverageScoreByRateeId(1L)).thenReturn(Optional.of(4.0));
            when(ratingRepository.countByRateeId(1L)).thenReturn(1L);
            when(userRepository.save(any(User.class))).thenReturn(rider);

            RatingRequest request = RatingRequest.builder()
                    .rideId(100L).score(4).comment("Polite passenger").build();

            RatingResponse response = ratingService.submitRating(2L, request);

            assertNotNull(response);
            assertEquals(4, response.getScore());
            assertEquals(2L, response.getRaterId());
            assertEquals(1L, response.getRateeId());
        }

        @Test
        @DisplayName("Cannot rate non-completed ride")
        void rateNonCompletedRide_fails() {
            ride.setStatus(RideStatus.IN_PROGRESS);
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));

            assertThrows(BadRequestException.class,
                    () -> ratingService.submitRating(1L,
                            RatingRequest.builder().rideId(100L).score(5).build()));
        }

        @Test
        @DisplayName("Cannot rate the same ride twice")
        void duplicateRating_fails() {
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
            when(userRepository.findById(1L)).thenReturn(Optional.of(rider));
            when(ratingRepository.existsByRideIdAndRaterId(100L, 1L)).thenReturn(true);

            assertThrows(ConflictException.class,
                    () -> ratingService.submitRating(1L,
                            RatingRequest.builder().rideId(100L).score(5).build()));
        }

        @Test
        @DisplayName("Non-participant cannot rate")
        void nonParticipant_fails() {
            User stranger = User.builder().id(999L).name("Stranger").build();
            when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
            when(userRepository.findById(999L)).thenReturn(Optional.of(stranger));

            assertThrows(BadRequestException.class,
                    () -> ratingService.submitRating(999L,
                            RatingRequest.builder().rideId(100L).score(3).build()));
        }

        @Test
        @DisplayName("Ride not found throws exception")
        void rideNotFound_fails() {
            when(rideRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(com.ridesharing.shared.exceptions.ResourceNotFoundException.class,
                    () -> ratingService.submitRating(1L,
                            RatingRequest.builder().rideId(999L).score(5).build()));
        }
    }

    @Nested
    @DisplayName("Get Ratings")
    class GetRatingsTests {

        @Test
        @DisplayName("Get ratings received by user")
        void getRatingsReceived() {
            Rating rating = Rating.builder().id(1L).ride(ride)
                    .rater(rider).ratee(driver).score(5)
                    .createdAt(LocalDateTime.now()).build();
            when(ratingRepository.findByRateeIdOrderByCreatedAtDesc(2L)).thenReturn(List.of(rating));

            List<RatingResponse> result = ratingService.getRatingsReceived(2L);

            assertEquals(1, result.size());
            assertEquals(5, result.get(0).getScore());
        }

        @Test
        @DisplayName("Get ratings given by user")
        void getRatingsGiven() {
            Rating rating = Rating.builder().id(1L).ride(ride)
                    .rater(rider).ratee(driver).score(4)
                    .createdAt(LocalDateTime.now()).build();
            when(ratingRepository.findByRaterIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(rating));

            List<RatingResponse> result = ratingService.getRatingsGiven(1L);

            assertEquals(1, result.size());
            assertEquals(4, result.get(0).getScore());
        }

        @Test
        @DisplayName("Get average rating")
        void getAverageRating() {
            when(ratingRepository.findAverageScoreByRateeId(2L)).thenReturn(Optional.of(4.5));

            double avg = ratingService.getAverageRating(2L);

            assertEquals(4.5, avg);
        }

        @Test
        @DisplayName("Average rating returns 0 when no ratings")
        void getAverageRating_noRatings() {
            when(ratingRepository.findAverageScoreByRateeId(2L)).thenReturn(Optional.empty());

            double avg = ratingService.getAverageRating(2L);

            assertEquals(0.0, avg);
        }
    }
}
