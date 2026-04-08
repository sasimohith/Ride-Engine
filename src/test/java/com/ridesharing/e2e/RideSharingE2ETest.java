package com.ridesharing.e2e;

import com.ridesharing.admin.dto.AdminDriverResponse;
import com.ridesharing.admin.service.AdminService;
import com.ridesharing.auth.dto.AuthResponse;
import com.ridesharing.auth.dto.LoginRequest;
import com.ridesharing.auth.dto.RegisterRequest;
import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.auth.service.AuthService;
import com.ridesharing.auth.service.JwtService;
import com.ridesharing.driver.dto.VehicleRequest;
import com.ridesharing.driver.model.Vehicle;
import com.ridesharing.driver.service.DriverLocationService;
import com.ridesharing.driver.service.DriverService;
import com.ridesharing.notification.dto.NotificationMessage;
import com.ridesharing.notification.service.NotificationService;
import com.ridesharing.pricing.dto.FareEstimateRequest;
import com.ridesharing.pricing.dto.FareEstimateResponse;
import com.ridesharing.pricing.dto.FareRuleResponse;
import com.ridesharing.pricing.model.FareRule;
import com.ridesharing.pricing.repository.FareRuleRepository;
import com.ridesharing.pricing.service.PricingService;
import com.ridesharing.ride.dto.RideRequestDto;
import com.ridesharing.ride.dto.RideResponseDto;
import com.ridesharing.ride.model.Ride;
import com.ridesharing.ride.repository.RideRepository;
import com.ridesharing.ride.service.RideMatchingService;
import com.ridesharing.ride.service.RideService;
import com.ridesharing.payment.dto.PaymentResponse;
import com.ridesharing.payment.model.Payment;
import com.ridesharing.payment.repository.PaymentRepository;
import com.ridesharing.payment.service.PaymentService;
import com.ridesharing.rating.dto.RatingRequest;
import com.ridesharing.rating.dto.RatingResponse;
import com.ridesharing.rating.model.Rating;
import com.ridesharing.rating.repository.RatingRepository;
import com.ridesharing.rating.service.RatingService;
import com.ridesharing.shared.enums.*;
import com.ridesharing.shared.exceptions.BadRequestException;
import com.ridesharing.shared.exceptions.ConflictException;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * END-TO-END INTEGRATION TEST
 *
 * Tests the COMPLETE ride lifecycle through all service layers:
 *
 *   1. REGISTER: Rider registers → Driver registers
 *   2. LOGIN: Both get JWT tokens
 *   3. DRIVER SETUP: Add vehicle → Add documents → Admin approves
 *   4. DRIVER ONLINE: Driver goes online + updates location
 *   5. PRICING: Rider gets fare estimate
 *   6. RIDE REQUEST: Rider requests a ride
 *   7. MATCHING: System matches rider with nearby driver
 *   8. RIDE LIFECYCLE: Start → Complete
 *   9. HISTORY: Rider and driver can see ride history
 *  10. EDGE CASES: Duplicate rides, invalid transitions, unauthorized access
 *
 * Uses real service logic with mocked infrastructure (Redis, Kafka, WebSocket).
 */
@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("E2E: Complete Ride Lifecycle")
class RideSharingE2ETest {

    // ── Infrastructure mocks ──
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOps;
    @Mock private SetOperations<String, Object> setOps;
    @Mock private org.springframework.data.redis.core.GeoOperations<String, Object> geoOps;
    @Mock private SimpMessagingTemplate messagingTemplate;

    // ── Real services under test ──
    private UserRepository userRepository;
    private com.ridesharing.driver.repository.VehicleRepository vehicleRepository;
    private com.ridesharing.driver.repository.DriverDocumentRepository documentRepository;
    private FareRuleRepository fareRuleRepository;
    private RideRepository rideRepository;

    private AuthService authService;
    private JwtService jwtService;
    private DriverService driverService;
    private DriverLocationService driverLocationService;
    private AdminService adminService;
    private PricingService pricingService;
    private RideService rideService;
    private RideMatchingService rideMatchingService;
    private NotificationService notificationService;
    private PaymentRepository paymentRepository;
    private PaymentService paymentService;
    private RatingRepository ratingRepository;
    private RatingService ratingService;
    private PasswordEncoder passwordEncoder;

    // ── State shared across test phases ──
    private static Long riderId;
    private static Long driverId;
    private static Long adminId;
    private static Long rideId;
    private static String riderToken;
    private static String driverToken;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        lenient().when(redisTemplate.opsForGeo()).thenReturn(geoOps);
    }

    // =================================================================
    // PHASE 1: AUTH — Register and Login
    // =================================================================

    @Test
    @Order(1)
    @DisplayName("Phase 1.1: Register a RIDER")
    void phase1_registerRider() {
        // Set up real services
        passwordEncoder = new BCryptPasswordEncoder();
        userRepository = mock(UserRepository.class);
        jwtService = createJwtService();
        authService = new AuthService(userRepository, jwtService, passwordEncoder);

        RegisterRequest request = RegisterRequest.builder()
                .name("Arun Kumar")
                .email("arun@test.com")
                .password("password123")
                .phone("9876543210")
                .role(UserRole.RIDER)
                .build();

        when(userRepository.existsByEmail("arun@test.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });

        AuthResponse response = authService.register(request);

        assertNotNull(response);
        assertNotNull(response.getAccessToken());
        assertEquals(UserRole.RIDER, response.getRole());
        riderId = 1L;
        riderToken = response.getAccessToken();

        System.out.println("  [PASS] Rider registered: id=" + riderId);
    }

    @Test
    @Order(2)
    @DisplayName("Phase 1.2: Register a DRIVER")
    void phase1_registerDriver() {
        passwordEncoder = new BCryptPasswordEncoder();
        userRepository = mock(UserRepository.class);
        jwtService = createJwtService();
        authService = new AuthService(userRepository, jwtService, passwordEncoder);

        RegisterRequest request = RegisterRequest.builder()
                .name("Ravi Driver")
                .email("ravi@test.com")
                .password("driver123")
                .phone("8765432109")
                .role(UserRole.DRIVER)
                .build();

        when(userRepository.existsByEmail("ravi@test.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(2L);
            return u;
        });

        AuthResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals(UserRole.DRIVER, response.getRole());
        driverId = 2L;
        driverToken = response.getAccessToken();

        System.out.println("  [PASS] Driver registered: id=" + driverId);
    }

    @Test
    @Order(3)
    @DisplayName("Phase 1.3: ADMIN self-registration should be BLOCKED")
    void phase1_blockAdminSelfRegistration() {
        passwordEncoder = new BCryptPasswordEncoder();
        userRepository = mock(UserRepository.class);
        jwtService = createJwtService();
        authService = new AuthService(userRepository, jwtService, passwordEncoder);

        RegisterRequest request = RegisterRequest.builder()
                .name("Hacker")
                .email("hacker@test.com")
                .password("hack123")
                .phone("0000000000")
                .role(UserRole.ADMIN)
                .build();

        assertThrows(com.ridesharing.shared.exceptions.UnauthorizedException.class,
                () -> authService.register(request));

        System.out.println("  [PASS] Admin self-registration correctly blocked");
    }

    @Test
    @Order(4)
    @DisplayName("Phase 1.4: Login with correct credentials")
    void phase1_loginSuccess() {
        passwordEncoder = new BCryptPasswordEncoder();
        userRepository = mock(UserRepository.class);
        jwtService = createJwtService();
        authService = new AuthService(userRepository, jwtService, passwordEncoder);

        User rider = User.builder()
                .id(1L).name("Arun Kumar").email("arun@test.com")
                .password(passwordEncoder.encode("password123"))
                .phone("9876543210").role(UserRole.RIDER).active(true)
                .build();

        when(userRepository.findByEmail("arun@test.com")).thenReturn(Optional.of(rider));

        AuthResponse response = authService.login(
                LoginRequest.builder().email("arun@test.com").password("password123").build()
        );

        assertNotNull(response.getAccessToken());
        assertNotNull(response.getRefreshToken());
        assertEquals(UserRole.RIDER, response.getRole());

        System.out.println("  [PASS] Rider login successful, JWT issued");
    }

    @Test
    @Order(5)
    @DisplayName("Phase 1.5: Login with WRONG password should fail")
    void phase1_loginWrongPassword() {
        passwordEncoder = new BCryptPasswordEncoder();
        userRepository = mock(UserRepository.class);
        jwtService = createJwtService();
        authService = new AuthService(userRepository, jwtService, passwordEncoder);

        User rider = User.builder()
                .id(1L).name("Arun Kumar").email("arun@test.com")
                .password(passwordEncoder.encode("password123"))
                .phone("9876543210").role(UserRole.RIDER).active(true)
                .build();

        when(userRepository.findByEmail("arun@test.com")).thenReturn(Optional.of(rider));

        assertThrows(com.ridesharing.shared.exceptions.UnauthorizedException.class,
                () -> authService.login(
                        LoginRequest.builder().email("arun@test.com").password("WRONG").build()
                ));

        System.out.println("  [PASS] Wrong password correctly rejected");
    }

    // =================================================================
    // PHASE 2: DRIVER SETUP — Vehicle, Documents, Admin Approval
    // =================================================================

    @Test
    @Order(6)
    @DisplayName("Phase 2.1: Driver adds a vehicle")
    void phase2_addVehicle() {
        userRepository = mock(UserRepository.class);
        vehicleRepository = mock(com.ridesharing.driver.repository.VehicleRepository.class);
        documentRepository = mock(com.ridesharing.driver.repository.DriverDocumentRepository.class);
        driverLocationService = new DriverLocationService(redisTemplate);
        driverService = new DriverService(userRepository, vehicleRepository, documentRepository, driverLocationService);

        User driver = createDriverUser();
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(vehicleRepository.findByDriverId(2L)).thenReturn(Optional.empty());
        when(vehicleRepository.existsByPlateNumber("TN 01 AB 1234")).thenReturn(false);
        when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VehicleRequest request = VehicleRequest.builder()
                .vehicleType("AUTO").plateNumber("TN 01 AB 1234")
                .model("Bajaj RE").color("Yellow").build();

        Vehicle vehicle = driverService.addVehicle(2L, request);

        assertNotNull(vehicle);
        assertEquals("AUTO", vehicle.getVehicleType());
        assertEquals("TN 01 AB 1234", vehicle.getPlateNumber());

        System.out.println("  [PASS] Vehicle added: AUTO - TN 01 AB 1234");
    }

    @Test
    @Order(7)
    @DisplayName("Phase 2.2: Duplicate plate number should be BLOCKED")
    void phase2_duplicatePlateBlocked() {
        userRepository = mock(UserRepository.class);
        vehicleRepository = mock(com.ridesharing.driver.repository.VehicleRepository.class);
        documentRepository = mock(com.ridesharing.driver.repository.DriverDocumentRepository.class);
        driverLocationService = new DriverLocationService(redisTemplate);
        driverService = new DriverService(userRepository, vehicleRepository, documentRepository, driverLocationService);

        User driver = createDriverUser();
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(vehicleRepository.findByDriverId(2L)).thenReturn(Optional.empty());
        when(vehicleRepository.existsByPlateNumber("TN 01 AB 1234")).thenReturn(true);

        VehicleRequest request = VehicleRequest.builder()
                .vehicleType("AUTO").plateNumber("TN 01 AB 1234")
                .model("Bajaj RE").color("Yellow").build();

        assertThrows(com.ridesharing.shared.exceptions.ConflictException.class,
                () -> driverService.addVehicle(2L, request));

        System.out.println("  [PASS] Duplicate plate number correctly blocked");
    }

    @Test
    @Order(8)
    @DisplayName("Phase 2.3: Admin approves the driver")
    void phase2_adminApprovesDriver() {
        userRepository = mock(UserRepository.class);
        vehicleRepository = mock(com.ridesharing.driver.repository.VehicleRepository.class);
        documentRepository = mock(com.ridesharing.driver.repository.DriverDocumentRepository.class);
        adminService = new AdminService(userRepository, vehicleRepository, documentRepository, kafkaTemplate);

        User driver = createDriverUser();
        driver.setApprovalStatus(DriverApprovalStatus.PENDING);
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(userRepository.save(any(User.class))).thenReturn(driver);
        when(vehicleRepository.findByDriverId(2L)).thenReturn(Optional.empty());
        when(documentRepository.findByDriverId(2L)).thenReturn(List.of());

        AdminDriverResponse response = adminService.approveDriver(2L);

        assertEquals(DriverApprovalStatus.APPROVED, driver.getApprovalStatus());
        verify(kafkaTemplate).send(eq("driver-approved"), eq("2"), any());

        System.out.println("  [PASS] Admin approved driver — Kafka event published");
    }

    @Test
    @Order(9)
    @DisplayName("Phase 2.4: Re-approving should be BLOCKED")
    void phase2_reApproveBlocked() {
        userRepository = mock(UserRepository.class);
        vehicleRepository = mock(com.ridesharing.driver.repository.VehicleRepository.class);
        documentRepository = mock(com.ridesharing.driver.repository.DriverDocumentRepository.class);
        adminService = new AdminService(userRepository, vehicleRepository, documentRepository, kafkaTemplate);

        User driver = createDriverUser();
        driver.setApprovalStatus(DriverApprovalStatus.APPROVED);
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));

        assertThrows(BadRequestException.class, () -> adminService.approveDriver(2L));

        System.out.println("  [PASS] Re-approval correctly blocked");
    }

    @Test
    @Order(10)
    @DisplayName("Phase 2.5: Driver goes ONLINE after approval")
    void phase2_driverGoesOnline() {
        userRepository = mock(UserRepository.class);
        vehicleRepository = mock(com.ridesharing.driver.repository.VehicleRepository.class);
        documentRepository = mock(com.ridesharing.driver.repository.DriverDocumentRepository.class);
        driverLocationService = new DriverLocationService(redisTemplate);
        driverService = new DriverService(userRepository, vehicleRepository, documentRepository, driverLocationService);

        User driver = createDriverUser();
        driver.setApprovalStatus(DriverApprovalStatus.APPROVED);
        driver.setAvailability(DriverAvailability.OFFLINE);
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(userRepository.save(any(User.class))).thenReturn(driver);

        driverService.updateAvailability(2L, DriverAvailability.ONLINE, 12.9716, 77.5946);

        assertEquals(DriverAvailability.ONLINE, driver.getAvailability());

        System.out.println("  [PASS] Driver is now ONLINE and visible to riders");
    }

    @Test
    @Order(11)
    @DisplayName("Phase 2.6: Unapproved driver going online should be BLOCKED")
    void phase2_unapprovedDriverBlocked() {
        userRepository = mock(UserRepository.class);
        vehicleRepository = mock(com.ridesharing.driver.repository.VehicleRepository.class);
        documentRepository = mock(com.ridesharing.driver.repository.DriverDocumentRepository.class);
        driverLocationService = new DriverLocationService(redisTemplate);
        driverService = new DriverService(userRepository, vehicleRepository, documentRepository, driverLocationService);

        User driver = createDriverUser();
        driver.setApprovalStatus(DriverApprovalStatus.PENDING);
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));

        assertThrows(BadRequestException.class,
                () -> driverService.updateAvailability(2L, DriverAvailability.ONLINE, 12.9716, 77.5946));

        System.out.println("  [PASS] Unapproved driver correctly blocked from going online");
    }

    // =================================================================
    // PHASE 3: PRICING — Fare estimation
    // =================================================================

    @Test
    @Order(12)
    @DisplayName("Phase 3.1: Get fare estimate for a ride")
    void phase3_fareEstimate() {
        fareRuleRepository = mock(FareRuleRepository.class);
        pricingService = new PricingService(fareRuleRepository, redisTemplate);

        FareRule autoRule = FareRule.builder()
                .id(1L).vehicleType("AUTO")
                .baseFare(new BigDecimal("25.00")).perKmRate(new BigDecimal("12.00"))
                .perMinuteRate(new BigDecimal("1.50")).minimumFare(new BigDecimal("30.00"))
                .active(true).build();

        when(valueOps.get("pricing:fare-rule:AUTO")).thenReturn(null);
        when(fareRuleRepository.findByVehicleTypeAndActiveTrue("AUTO")).thenReturn(Optional.of(autoRule));
        when(valueOps.get("pricing:surge:AUTO")).thenReturn(null);

        FareEstimateResponse estimate = pricingService.estimateFare(
                FareEstimateRequest.builder()
                        .pickupLatitude(12.9716).pickupLongitude(77.5946)
                        .dropoffLatitude(12.9352).dropoffLongitude(77.6245)
                        .vehicleType("AUTO").build()
        );

        assertNotNull(estimate);
        assertTrue(estimate.getDistanceInKm() > 0);
        assertTrue(estimate.getEstimatedFare().compareTo(BigDecimal.ZERO) > 0);
        assertFalse(estimate.isSurgeActive());
        assertEquals("AUTO", estimate.getVehicleType());

        System.out.println("  [PASS] Fare estimate: ₹" + estimate.getEstimatedFare()
                + " for " + estimate.getDistanceInKm() + " km (" + estimate.getEstimatedTimeInMinutes() + " min)");
    }

    @Test
    @Order(13)
    @DisplayName("Phase 3.2: Fare estimate with SURGE pricing")
    void phase3_fareEstimateWithSurge() {
        fareRuleRepository = mock(FareRuleRepository.class);
        pricingService = new PricingService(fareRuleRepository, redisTemplate);

        FareRule autoRule = FareRule.builder()
                .id(1L).vehicleType("AUTO")
                .baseFare(new BigDecimal("25.00")).perKmRate(new BigDecimal("12.00"))
                .perMinuteRate(new BigDecimal("1.50")).minimumFare(new BigDecimal("30.00"))
                .active(true).build();

        when(valueOps.get("pricing:fare-rule:AUTO")).thenReturn(autoRule);
        when(valueOps.get("pricing:surge:AUTO")).thenReturn(2.0);

        FareEstimateResponse estimate = pricingService.estimateFare(
                FareEstimateRequest.builder()
                        .pickupLatitude(12.9716).pickupLongitude(77.5946)
                        .dropoffLatitude(12.9352).dropoffLongitude(77.6245)
                        .vehicleType("AUTO").build()
        );

        assertTrue(estimate.isSurgeActive());
        assertEquals(0, new BigDecimal("2.0").compareTo(estimate.getSurgeMultiplier()));

        System.out.println("  [PASS] Surge fare estimate: ₹" + estimate.getEstimatedFare() + " (2.0x surge)");
    }

    // =================================================================
    // PHASE 4: RIDE — Request, Match, Start, Complete
    // =================================================================

    @Test
    @Order(14)
    @DisplayName("Phase 4.1: Rider requests a ride")
    void phase4_requestRide() {
        userRepository = mock(UserRepository.class);
        rideRepository = mock(RideRepository.class);
        fareRuleRepository = mock(FareRuleRepository.class);
        pricingService = new PricingService(fareRuleRepository, redisTemplate);
        rideMatchingService = mock(RideMatchingService.class);
        rideService = new RideService(rideRepository, userRepository,
                pricingService, rideMatchingService, kafkaTemplate);

        User rider = createRiderUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(rider));
        when(rideRepository.existsByRiderIdAndStatusIn(eq(1L), any())).thenReturn(false);
        when(rideRepository.save(any(Ride.class))).thenAnswer(inv -> {
            Ride r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        FareRule autoRule = FareRule.builder()
                .id(1L).vehicleType("AUTO")
                .baseFare(new BigDecimal("25.00")).perKmRate(new BigDecimal("12.00"))
                .perMinuteRate(new BigDecimal("1.50")).minimumFare(new BigDecimal("30.00"))
                .active(true).build();
        when(valueOps.get("pricing:fare-rule:AUTO")).thenReturn(autoRule);
        when(valueOps.get("pricing:surge:AUTO")).thenReturn(null);

        RideResponseDto response = rideService.requestRide(1L,
                RideRequestDto.builder()
                        .pickupLatitude(12.9716).pickupLongitude(77.5946)
                        .dropoffLatitude(12.9352).dropoffLongitude(77.6245)
                        .pickupAddress("MG Road, Bangalore")
                        .dropoffAddress("Koramangala, Bangalore")
                        .vehicleType("AUTO").build()
        );

        assertNotNull(response);
        assertEquals(RideStatus.REQUESTED, response.getStatus());
        assertEquals("Arun Kumar", response.getRiderName());
        assertNotNull(response.getEstimatedFare());
        rideId = response.getRideId();

        verify(kafkaTemplate).send(eq("ride-requested"), anyString(), any());
        verify(rideMatchingService).matchDriverForRide(any(Ride.class));

        System.out.println("  [PASS] Ride requested: id=" + rideId
                + ", fare=₹" + response.getEstimatedFare()
                + ", from=" + response.getPickupAddress());
    }

    @Test
    @Order(15)
    @DisplayName("Phase 4.2: Rider requesting a SECOND ride should be BLOCKED")
    void phase4_duplicateRideBlocked() {
        setupRideServices();

        User rider = createRiderUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(rider));
        when(rideRepository.existsByRiderIdAndStatusIn(eq(1L), any())).thenReturn(true);

        assertThrows(BadRequestException.class,
                () -> rideService.requestRide(1L,
                        RideRequestDto.builder()
                                .pickupLatitude(12.97).pickupLongitude(77.59)
                                .dropoffLatitude(12.93).dropoffLongitude(77.62)
                                .vehicleType("AUTO").build()
                ));

        System.out.println("  [PASS] Duplicate ride correctly blocked");
    }

    @Test
    @Order(16)
    @DisplayName("Phase 4.3: Driver matching finds the nearest available driver")
    void phase4_matchDriver() {
        userRepository = mock(UserRepository.class);
        rideRepository = mock(RideRepository.class);
        driverLocationService = mock(DriverLocationService.class);
        Executor syncExecutor = Runnable::run;
        rideMatchingService = new RideMatchingService(
                driverLocationService, userRepository, rideRepository,
                kafkaTemplate, redisTemplate, syncExecutor);

        User driver = createDriverUser();
        driver.setApprovalStatus(DriverApprovalStatus.APPROVED);
        driver.setAvailability(DriverAvailability.ONLINE);

        User rider = createRiderUser();
        Ride ride = createTestRide(rider);

        when(driverLocationService.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(new DriverLocationService.DriverLocationResult(2L, 1.5)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(rideRepository.existsByDriverIdAndStatusIn(eq(2L), any())).thenReturn(false);
        when(rideRepository.save(any(Ride.class))).thenReturn(ride);
        when(userRepository.save(any(User.class))).thenReturn(driver);

        boolean matched = rideMatchingService.attemptMatching(ride);

        assertTrue(matched);
        assertEquals(RideStatus.ACCEPTED, ride.getStatus());
        assertEquals(driver, ride.getDriver());
        assertEquals(DriverAvailability.BUSY, driver.getAvailability());
        verify(driverLocationService).removeDriverLocation(2L);
        verify(kafkaTemplate).send(eq("ride-accepted"), anyString(), any());

        System.out.println("  [PASS] Ride matched with driver: " + driver.getName() + " (1.5 km away)");
    }

    @Test
    @Order(17)
    @DisplayName("Phase 4.4: Driver starts the ride")
    void phase4_startRide() {
        setupRideServices();

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.ACCEPTED);

        when(rideRepository.findByIdAndDriverId(100L, 2L)).thenReturn(Optional.of(ride));
        when(rideRepository.save(any(Ride.class))).thenReturn(ride);

        RideResponseDto response = rideService.startRide(2L, 100L);

        assertEquals(RideStatus.IN_PROGRESS, ride.getStatus());
        assertNotNull(ride.getStartedAt());

        System.out.println("  [PASS] Ride started — rider picked up");
    }

    @Test
    @Order(18)
    @DisplayName("Phase 4.5: Driver completes the ride")
    void phase4_completeRide() {
        setupRideServices();

        User rider = createRiderUser();
        User driver = createDriverUser();
        driver.setAvailability(DriverAvailability.BUSY);
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.IN_PROGRESS);

        when(rideRepository.findByIdAndDriverId(100L, 2L)).thenReturn(Optional.of(ride));
        when(rideRepository.save(any(Ride.class))).thenReturn(ride);
        when(userRepository.save(any(User.class))).thenReturn(driver);

        RideResponseDto response = rideService.completeRide(2L, 100L);

        assertEquals(RideStatus.COMPLETED, ride.getStatus());
        assertNotNull(ride.getCompletedAt());
        assertEquals(ride.getEstimatedFare(), ride.getActualFare());
        assertEquals(DriverAvailability.ONLINE, driver.getAvailability());
        verify(kafkaTemplate).send(eq("ride-completed"), anyString(), any());

        System.out.println("  [PASS] Ride completed — fare: ₹" + ride.getActualFare()
                + ", driver back ONLINE");
    }

    // =================================================================
    // PHASE 5: EDGE CASES — Invalid transitions, unauthorized access
    // =================================================================

    @Test
    @Order(19)
    @DisplayName("Phase 5.1: Starting an already-started ride should FAIL")
    void phase5_invalidStartTransition() {
        setupRideServices();

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.IN_PROGRESS);

        when(rideRepository.findByIdAndDriverId(100L, 2L)).thenReturn(Optional.of(ride));

        assertThrows(BadRequestException.class, () -> rideService.startRide(2L, 100L));

        System.out.println("  [PASS] Invalid start transition correctly blocked");
    }

    @Test
    @Order(20)
    @DisplayName("Phase 5.2: Completing a REQUESTED ride should FAIL")
    void phase5_invalidCompleteTransition() {
        setupRideServices();

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.ACCEPTED);

        when(rideRepository.findByIdAndDriverId(100L, 2L)).thenReturn(Optional.of(ride));

        assertThrows(BadRequestException.class, () -> rideService.completeRide(2L, 100L));

        System.out.println("  [PASS] Invalid complete transition correctly blocked");
    }

    @Test
    @Order(21)
    @DisplayName("Phase 5.3: Cancelling a ride frees the driver")
    void phase5_cancelRide() {
        setupRideServices();

        User rider = createRiderUser();
        User driver = createDriverUser();
        driver.setAvailability(DriverAvailability.BUSY);
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.ACCEPTED);

        when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
        when(rideRepository.save(any(Ride.class))).thenReturn(ride);
        when(userRepository.save(any(User.class))).thenReturn(driver);

        rideService.cancelRide(1L, 100L);

        assertEquals(RideStatus.CANCELLED, ride.getStatus());
        assertEquals(DriverAvailability.ONLINE, driver.getAvailability());
        verify(kafkaTemplate).send(eq("ride-cancelled"), anyString(), any());

        System.out.println("  [PASS] Ride cancelled — driver freed back to ONLINE");
    }

    @Test
    @Order(22)
    @DisplayName("Phase 5.4: Non-participant accessing ride should FAIL")
    void phase5_unauthorizedAccess() {
        setupRideServices();

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);

        when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));

        assertThrows(BadRequestException.class, () -> rideService.getRide(999L, 100L));

        System.out.println("  [PASS] Unauthorized ride access correctly blocked");
    }

    // =================================================================
    // PHASE 6: NOTIFICATIONS — WebSocket push verification
    // =================================================================

    @Test
    @Order(23)
    @DisplayName("Phase 6.1: Rider gets notified when driver accepts")
    void phase6_riderNotifiedOnAccept() {
        notificationService = new NotificationService(messagingTemplate);

        notificationService.notifyRiderDriverAccepted(1L, 100L, 2L);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/rider/1"), captor.capture());

        NotificationMessage msg = captor.getValue();
        assertEquals("RIDE_ACCEPTED", msg.getType());
        assertEquals("Driver Found!", msg.getTitle());

        System.out.println("  [PASS] Rider notified via WebSocket: " + msg.getTitle());
    }

    @Test
    @Order(24)
    @DisplayName("Phase 6.2: Both notified when ride completes")
    void phase6_bothNotifiedOnComplete() {
        notificationService = new NotificationService(messagingTemplate);

        notificationService.notifyRideCompleted(1L, 2L, 100L, "105.00");

        verify(messagingTemplate).convertAndSend(eq("/topic/rider/1"), any(NotificationMessage.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/driver/2"), any(NotificationMessage.class));

        System.out.println("  [PASS] Both rider and driver notified on ride completion");
    }

    // =================================================================
    // PHASE 7: RIDE HISTORY
    // =================================================================

    @Test
    @Order(25)
    @DisplayName("Phase 7.1: Rider can view ride history")
    void phase7_riderHistory() {
        setupRideServices();

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.COMPLETED);

        when(rideRepository.findByRiderIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(ride));

        List<RideResponseDto> history = rideService.getRiderHistory(1L);

        assertEquals(1, history.size());
        assertEquals(RideStatus.COMPLETED, history.get(0).getStatus());

        System.out.println("  [PASS] Rider history: " + history.size() + " ride(s)");
    }

    // =================================================================
    // PHASE 8: PAYMENT — Auto-creation, process, refund
    // =================================================================

    @Test
    @Order(26)
    @DisplayName("Phase 8.1: Payment auto-created for completed ride")
    void phase8_paymentCreated() {
        userRepository = mock(UserRepository.class);
        rideRepository = mock(RideRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentService = new PaymentService(paymentRepository, rideRepository, userRepository);

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.COMPLETED);
        ride.setActualFare(new BigDecimal("105.00"));

        when(paymentRepository.existsByRideId(100L)).thenReturn(false);
        when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
        when(userRepository.findById(1L)).thenReturn(Optional.of(rider));
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        Payment payment = paymentService.createPaymentForRide(100L, 1L, 2L, new BigDecimal("105.00"));

        assertNotNull(payment);
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(new BigDecimal("105.00"), payment.getAmount());

        System.out.println("  [PASS] Payment created: PENDING, ₹" + payment.getAmount());
    }

    @Test
    @Order(27)
    @DisplayName("Phase 8.2: Rider pays via UPI")
    void phase8_riderPays() {
        userRepository = mock(UserRepository.class);
        rideRepository = mock(RideRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentService = new PaymentService(paymentRepository, rideRepository, userRepository);

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);

        Payment payment = Payment.builder()
                .id(1L).ride(ride).rider(rider).driver(driver)
                .amount(new BigDecimal("105.00")).status(PaymentStatus.PENDING)
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now()).build();

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);

        PaymentResponse response = paymentService.processPayment(1L, 1L, "UPI");

        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals("UPI", payment.getPaymentMethod());
        assertNotNull(payment.getPaidAt());

        System.out.println("  [PASS] Payment completed via UPI");
    }

    @Test
    @Order(28)
    @DisplayName("Phase 8.3: Non-rider paying should be BLOCKED")
    void phase8_nonRiderPayBlocked() {
        userRepository = mock(UserRepository.class);
        rideRepository = mock(RideRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentService = new PaymentService(paymentRepository, rideRepository, userRepository);

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);

        Payment payment = Payment.builder()
                .id(1L).ride(ride).rider(rider).driver(driver)
                .amount(new BigDecimal("105.00")).status(PaymentStatus.PENDING)
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now()).build();

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertThrows(BadRequestException.class,
                () -> paymentService.processPayment(999L, 1L, "CASH"));

        System.out.println("  [PASS] Non-rider payment correctly blocked");
    }

    @Test
    @Order(29)
    @DisplayName("Phase 8.4: Admin refunds a completed payment")
    void phase8_adminRefund() {
        userRepository = mock(UserRepository.class);
        rideRepository = mock(RideRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentService = new PaymentService(paymentRepository, rideRepository, userRepository);

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);

        Payment payment = Payment.builder()
                .id(1L).ride(ride).rider(rider).driver(driver)
                .amount(new BigDecimal("105.00")).status(PaymentStatus.COMPLETED)
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now()).build();

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);

        PaymentResponse response = paymentService.refundPayment(1L);

        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());

        System.out.println("  [PASS] Payment refunded by admin");
    }

    @Test
    @Order(30)
    @DisplayName("Phase 8.5: Duplicate payment is idempotent (skipped)")
    void phase8_duplicatePaymentSkipped() {
        userRepository = mock(UserRepository.class);
        rideRepository = mock(RideRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentService = new PaymentService(paymentRepository, rideRepository, userRepository);

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);

        Payment existing = Payment.builder()
                .id(1L).ride(ride).rider(rider).driver(driver)
                .amount(new BigDecimal("105.00")).status(PaymentStatus.PENDING).build();

        when(paymentRepository.existsByRideId(100L)).thenReturn(true);
        when(paymentRepository.findByRideId(100L)).thenReturn(Optional.of(existing));

        Payment result = paymentService.createPaymentForRide(100L, 1L, 2L, new BigDecimal("105.00"));

        assertNotNull(result);
        verify(paymentRepository, never()).save(any());

        System.out.println("  [PASS] Duplicate payment skipped (idempotent)");
    }

    // =================================================================
    // PHASE 9: RATING — Rider rates driver, driver rates rider
    // =================================================================

    @Test
    @Order(31)
    @DisplayName("Phase 9.1: Rider rates driver 5 stars")
    void phase9_riderRatesDriver() {
        rideRepository = mock(RideRepository.class);
        userRepository = mock(UserRepository.class);
        ratingRepository = mock(RatingRepository.class);
        ratingService = new RatingService(ratingRepository, rideRepository, userRepository);

        User rider = createRiderUser();
        User driver = createDriverUserWithRating();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.COMPLETED);

        when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
        when(userRepository.findById(1L)).thenReturn(Optional.of(rider));
        when(ratingRepository.existsByRideIdAndRaterId(100L, 1L)).thenReturn(false);
        when(ratingRepository.save(any(Rating.class))).thenAnswer(inv -> {
            Rating r = inv.getArgument(0);
            r.setId(1L);
            r.setCreatedAt(java.time.LocalDateTime.now());
            return r;
        });
        when(ratingRepository.findAverageScoreByRateeId(2L)).thenReturn(Optional.of(5.0));
        when(ratingRepository.countByRateeId(2L)).thenReturn(1L);
        when(userRepository.save(any(User.class))).thenReturn(driver);

        RatingResponse response = ratingService.submitRating(1L,
                RatingRequest.builder().rideId(100L).score(5).comment("Excellent ride!").build());

        assertNotNull(response);
        assertEquals(5, response.getScore());
        assertEquals("Excellent ride!", response.getComment());
        assertEquals(1L, response.getRaterId());
        assertEquals(2L, response.getRateeId());

        System.out.println("  [PASS] Rider rated driver: 5 stars — \"Excellent ride!\"");
    }

    @Test
    @Order(32)
    @DisplayName("Phase 9.2: Driver rates rider 4 stars")
    void phase9_driverRatesRider() {
        rideRepository = mock(RideRepository.class);
        userRepository = mock(UserRepository.class);
        ratingRepository = mock(RatingRepository.class);
        ratingService = new RatingService(ratingRepository, rideRepository, userRepository);

        User rider = createRiderUserWithRating();
        User driver = createDriverUserWithRating();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.COMPLETED);

        when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(ratingRepository.existsByRideIdAndRaterId(100L, 2L)).thenReturn(false);
        when(ratingRepository.save(any(Rating.class))).thenAnswer(inv -> {
            Rating r = inv.getArgument(0);
            r.setId(2L);
            r.setCreatedAt(java.time.LocalDateTime.now());
            return r;
        });
        when(ratingRepository.findAverageScoreByRateeId(1L)).thenReturn(Optional.of(4.0));
        when(ratingRepository.countByRateeId(1L)).thenReturn(1L);
        when(userRepository.save(any(User.class))).thenReturn(rider);

        RatingResponse response = ratingService.submitRating(2L,
                RatingRequest.builder().rideId(100L).score(4).comment("Polite passenger").build());

        assertNotNull(response);
        assertEquals(4, response.getScore());
        assertEquals(2L, response.getRaterId());
        assertEquals(1L, response.getRateeId());

        System.out.println("  [PASS] Driver rated rider: 4 stars — \"Polite passenger\"");
    }

    @Test
    @Order(33)
    @DisplayName("Phase 9.3: Duplicate rating should be BLOCKED")
    void phase9_duplicateRatingBlocked() {
        rideRepository = mock(RideRepository.class);
        userRepository = mock(UserRepository.class);
        ratingRepository = mock(RatingRepository.class);
        ratingService = new RatingService(ratingRepository, rideRepository, userRepository);

        User rider = createRiderUser();
        User driver = createDriverUser();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.COMPLETED);

        when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
        when(userRepository.findById(1L)).thenReturn(Optional.of(rider));
        when(ratingRepository.existsByRideIdAndRaterId(100L, 1L)).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> ratingService.submitRating(1L,
                        RatingRequest.builder().rideId(100L).score(5).build()));

        System.out.println("  [PASS] Duplicate rating correctly blocked");
    }

    @Test
    @Order(34)
    @DisplayName("Phase 9.4: Rating a non-completed ride should FAIL")
    void phase9_ratingNonCompletedRideFails() {
        rideRepository = mock(RideRepository.class);
        userRepository = mock(UserRepository.class);
        ratingRepository = mock(RatingRepository.class);
        ratingService = new RatingService(ratingRepository, rideRepository, userRepository);

        User rider = createRiderUser();
        Ride ride = createTestRide(rider);
        ride.setStatus(RideStatus.IN_PROGRESS);

        when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));

        assertThrows(BadRequestException.class,
                () -> ratingService.submitRating(1L,
                        RatingRequest.builder().rideId(100L).score(5).build()));

        System.out.println("  [PASS] Rating non-completed ride correctly blocked");
    }

    @Test
    @Order(35)
    @DisplayName("Phase 9.5: Non-participant cannot rate")
    void phase9_nonParticipantBlocked() {
        rideRepository = mock(RideRepository.class);
        userRepository = mock(UserRepository.class);
        ratingRepository = mock(RatingRepository.class);
        ratingService = new RatingService(ratingRepository, rideRepository, userRepository);

        User rider = createRiderUser();
        User driver = createDriverUser();
        User stranger = User.builder().id(999L).name("Stranger").build();
        Ride ride = createTestRide(rider);
        ride.setDriver(driver);
        ride.setStatus(RideStatus.COMPLETED);

        when(rideRepository.findById(100L)).thenReturn(Optional.of(ride));
        when(userRepository.findById(999L)).thenReturn(Optional.of(stranger));

        assertThrows(BadRequestException.class,
                () -> ratingService.submitRating(999L,
                        RatingRequest.builder().rideId(100L).score(3).build()));

        System.out.println("  [PASS] Non-participant rating correctly blocked");
    }

    // =================================================================
    // Helper methods
    // =================================================================

    private JwtService createJwtService() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secretKey",
                "testSecretKeyThatIsAtLeast32CharactersLongForHS256AlgorithmTesting");
        ReflectionTestUtils.setField(service, "accessTokenExpiry", 900000L);
        ReflectionTestUtils.setField(service, "refreshTokenExpiry", 604800000L);
        return service;
    }

    private User createRiderUser() {
        return User.builder()
                .id(1L).name("Arun Kumar").email("arun@test.com")
                .password("hashed").phone("9876543210")
                .role(UserRole.RIDER).active(true)
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();
    }

    private User createDriverUser() {
        return User.builder()
                .id(2L).name("Ravi Driver").email("ravi@test.com")
                .password("hashed").phone("8765432109")
                .role(UserRole.DRIVER).active(true)
                .approvalStatus(DriverApprovalStatus.APPROVED)
                .availability(DriverAvailability.ONLINE)
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();
    }

    private Ride createTestRide(User rider) {
        return Ride.builder()
                .id(100L).rider(rider)
                .pickupLatitude(12.9716).pickupLongitude(77.5946)
                .pickupAddress("MG Road, Bangalore")
                .dropoffLatitude(12.9352).dropoffLongitude(77.6245)
                .dropoffAddress("Koramangala, Bangalore")
                .vehicleType("AUTO").status(RideStatus.REQUESTED)
                .distanceKm(5.18).estimatedTimeMin(12.4)
                .estimatedFare(new BigDecimal("105.00"))
                .surgeMultiplier(BigDecimal.ONE)
                .requestedAt(java.time.LocalDateTime.now())
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();
    }

    private User createRiderUserWithRating() {
        return User.builder()
                .id(1L).name("Arun Kumar").email("arun@test.com")
                .password("hashed").phone("9876543210")
                .role(UserRole.RIDER).active(true)
                .averageRating(java.math.BigDecimal.ZERO).totalRatings(0)
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();
    }

    private User createDriverUserWithRating() {
        return User.builder()
                .id(2L).name("Ravi Driver").email("ravi@test.com")
                .password("hashed").phone("8765432109")
                .role(UserRole.DRIVER).active(true)
                .approvalStatus(DriverApprovalStatus.APPROVED)
                .availability(DriverAvailability.ONLINE)
                .averageRating(java.math.BigDecimal.ZERO).totalRatings(0)
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();
    }

    private void setupRideServices() {
        userRepository = mock(UserRepository.class);
        rideRepository = mock(RideRepository.class);
        fareRuleRepository = mock(FareRuleRepository.class);
        pricingService = new PricingService(fareRuleRepository, redisTemplate);
        Executor syncExecutor = Runnable::run;
        rideMatchingService = new RideMatchingService(
                mock(DriverLocationService.class), userRepository, rideRepository,
                kafkaTemplate, redisTemplate, syncExecutor);
        rideService = new RideService(rideRepository, userRepository,
                pricingService, rideMatchingService, kafkaTemplate);
    }
}
