package com.ridesharing.auth.service;

import com.ridesharing.auth.dto.AuthResponse;
import com.ridesharing.auth.dto.LoginRequest;
import com.ridesharing.auth.dto.RegisterRequest;
import com.ridesharing.auth.model.User;
import com.ridesharing.auth.repository.UserRepository;
import com.ridesharing.shared.enums.UserRole;
import com.ridesharing.shared.exceptions.ConflictException;
import com.ridesharing.shared.exceptions.ResourceNotFoundException;
import com.ridesharing.shared.exceptions.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Business logic for authentication: register, login, refresh tokens.
 * This is where ALL the auth decisions happen.
 *
 * Coordinates between:
 *   - UserRepository (database operations)
 *   - JwtService (token generation/validation)
 *   - PasswordEncoder (BCrypt hashing)
 *
 * Controller calls this service. This service NEVER touches HTTP directly.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // Dependencies injected via constructor (not @Autowired on fields — better for testing)
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructor injection — Spring automatically provides these dependencies.
     * We use constructor injection (not @Autowired on fields) because:
     *   1. Makes dependencies explicit — you see exactly what this class needs
     *   2. Makes testing easier — you can pass mock objects in tests
     *   3. Ensures the object is fully initialized before use
     */
    public AuthService(UserRepository userRepository, JwtService jwtService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a new user (Rider or Driver) in the system.
     *
     * Steps:
     *   1. Check if email already exists → throw ConflictException if yes
     *   2. Hash the password with BCrypt
     *   3. Prevent self-registration as ADMIN (security measure)
     *   4. Save user to PostgreSQL
     *   5. Generate JWT tokens
     *   6. Return tokens to client
     *
     * @param request registration data from the client
     * @return AuthResponse with access token, refresh token, and role
     * @throws ConflictException if email is already registered
     */
    public AuthResponse register(RegisterRequest request) {
        log.info("Registration attempt for email: {}", request.getEmail());

        // Step 1: Check for duplicate email
        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration failed — email already exists: {}", request.getEmail());
            throw new ConflictException("Email already registered: " + request.getEmail());
        }

        // Step 2: Prevent self-registration as ADMIN
        if (request.getRole() == UserRole.ADMIN) {
            log.warn("Attempt to self-register as ADMIN blocked: {}", request.getEmail());
            throw new UnauthorizedException("Cannot self-register as ADMIN");
        }

        // Step 3: Build the User entity with hashed password
        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .phone(request.getPhone())
                .role(request.getRole())
                .active(true)
                .build();

        // Step 4: Save to PostgreSQL
        User savedUser = userRepository.save(user);
        log.info("User registered successfully: id={}, role={}", savedUser.getId(), savedUser.getRole());

        // Step 5: Generate tokens and return
        return generateAuthResponse(savedUser);
    }

    /**
     * Authenticates a user by email and password.
     *
     * Steps:
     *   1. Find user by email → throw ResourceNotFoundException if not found
     *   2. Check if account is active → throw UnauthorizedException if suspended
     *   3. Compare provided password with stored BCrypt hash
     *   4. Generate JWT tokens
     *   5. Return tokens to client
     *
     * @param request login credentials from the client
     * @return AuthResponse with access token, refresh token, and role
     * @throws ResourceNotFoundException if email not found
     * @throws UnauthorizedException if password is wrong or account is suspended
     */
    public AuthResponse login(LoginRequest request) {
        log.info("Login attempt for email: {}", request.getEmail());

        // Step 1: Find user by email
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    log.warn("Login failed — email not found: {}", request.getEmail());
                    return new ResourceNotFoundException("User", "email", request.getEmail());
                });

        // Step 2: Check if account is active
        if (!user.isActive()) {
            log.warn("Login failed — account suspended: {}", request.getEmail());
            throw new UnauthorizedException("Account is suspended. Contact support.");
        }

        // Step 3: Verify password
        // passwordEncoder.matches() compares raw password with BCrypt hash
        // It returns true/false — we never decrypt the hash (that's impossible with BCrypt)
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("Login failed — wrong password for email: {}", request.getEmail());
            throw new UnauthorizedException("Invalid email or password");
        }

        log.info("Login successful: userId={}, role={}", user.getId(), user.getRole());

        // Step 4: Generate tokens and return
        return generateAuthResponse(user);
    }

    /**
     * Creates the AuthResponse with fresh JWT tokens.
     * Both register and login use this to avoid code duplication.
     *
     * @param user the authenticated/registered user
     * @return AuthResponse containing tokens and role
     */
    private AuthResponse generateAuthResponse(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getRole().name());
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getRole().name());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .role(user.getRole())
                .build();
    }
}
