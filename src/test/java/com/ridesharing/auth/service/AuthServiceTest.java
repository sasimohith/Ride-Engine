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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for AuthService — register and login business logic.
 *
 * Uses Mockito to FAKE (mock) all dependencies:
 *   - UserRepository → fake database (no real PostgreSQL needed)
 *   - JwtService → fake token generation (returns predictable strings)
 *   - PasswordEncoder → fake hashing (returns predictable hashes)
 *
 * This makes tests:
 *   - FAST (no DB, no network)
 *   - RELIABLE (no flaky connections)
 *   - FOCUSED (testing only AuthService logic, not JPA or BCrypt)
 *
 * Happy path: register/login with valid data
 * Failure 1: register with duplicate email → ConflictException
 * Failure 2: login with wrong password → UnauthorizedException
 * Failure 3: login with non-existent email → ResourceNotFoundException
 * Edge case: register as ADMIN → blocked
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Tests")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;
    private User savedUser;

    @BeforeEach
    void setUp() {
        registerRequest = RegisterRequest.builder()
                .name("Raj Kumar")
                .email("raj@example.com")
                .password("securePass123")
                .phone("9876543210")
                .role(UserRole.RIDER)
                .build();

        loginRequest = LoginRequest.builder()
                .email("raj@example.com")
                .password("securePass123")
                .build();

        savedUser = User.builder()
                .id(1L)
                .name("Raj Kumar")
                .email("raj@example.com")
                .password("$2a$10$hashedPassword")
                .phone("9876543210")
                .role(UserRole.RIDER)
                .active(true)
                .build();
    }

    // ── REGISTER TESTS ──

    @Test
    @DisplayName("should_registerSuccessfully_when_validDataProvided")
    void should_registerSuccessfully_when_validDataProvided() {
        // GIVEN: email doesn't exist, password gets hashed, user gets saved
        when(userRepository.existsByEmail("raj@example.com")).thenReturn(false);
        when(passwordEncoder.encode("securePass123")).thenReturn("$2a$10$hashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateAccessToken(1L, "RIDER")).thenReturn("access-token");
        when(jwtService.generateRefreshToken(1L, "RIDER")).thenReturn("refresh-token");

        // WHEN
        AuthResponse response = authService.register(registerRequest);

        // THEN
        assertNotNull(response);
        assertEquals("access-token", response.getAccessToken());
        assertEquals("refresh-token", response.getRefreshToken());
        assertEquals(UserRole.RIDER, response.getRole());
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("should_throwConflictException_when_emailAlreadyExists")
    void should_throwConflictException_when_emailAlreadyExists() {
        // GIVEN: email already exists in database
        when(userRepository.existsByEmail("raj@example.com")).thenReturn(true);

        // WHEN / THEN
        ConflictException ex = assertThrows(ConflictException.class,
                () -> authService.register(registerRequest));

        assertEquals("Email already registered: raj@example.com", ex.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("should_throwUnauthorizedException_when_registeringAsAdmin")
    void should_throwUnauthorizedException_when_registeringAsAdmin() {
        // GIVEN: someone tries to self-register as ADMIN
        registerRequest.setRole(UserRole.ADMIN);
        when(userRepository.existsByEmail("raj@example.com")).thenReturn(false);

        // WHEN / THEN
        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> authService.register(registerRequest));

        assertEquals("Cannot self-register as ADMIN", ex.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    // ── LOGIN TESTS ──

    @Test
    @DisplayName("should_loginSuccessfully_when_validCredentials")
    void should_loginSuccessfully_when_validCredentials() {
        // GIVEN: user exists, password matches
        when(userRepository.findByEmail("raj@example.com")).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("securePass123", "$2a$10$hashedPassword")).thenReturn(true);
        when(jwtService.generateAccessToken(1L, "RIDER")).thenReturn("access-token");
        when(jwtService.generateRefreshToken(1L, "RIDER")).thenReturn("refresh-token");

        // WHEN
        AuthResponse response = authService.login(loginRequest);

        // THEN
        assertNotNull(response);
        assertEquals("access-token", response.getAccessToken());
        assertEquals(UserRole.RIDER, response.getRole());
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_emailNotFound")
    void should_throwResourceNotFoundException_when_emailNotFound() {
        // GIVEN: email doesn't exist in database
        when(userRepository.findByEmail("raj@example.com")).thenReturn(Optional.empty());

        // WHEN / THEN
        assertThrows(ResourceNotFoundException.class,
                () -> authService.login(loginRequest));
    }

    @Test
    @DisplayName("should_throwUnauthorizedException_when_wrongPassword")
    void should_throwUnauthorizedException_when_wrongPassword() {
        // GIVEN: user exists but password doesn't match
        when(userRepository.findByEmail("raj@example.com")).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("securePass123", "$2a$10$hashedPassword")).thenReturn(false);

        // WHEN / THEN
        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> authService.login(loginRequest));

        assertEquals("Invalid email or password", ex.getMessage());
    }

    @Test
    @DisplayName("should_throwUnauthorizedException_when_accountIsSuspended")
    void should_throwUnauthorizedException_when_accountIsSuspended() {
        // GIVEN: user exists but account is suspended
        savedUser.setActive(false);
        when(userRepository.findByEmail("raj@example.com")).thenReturn(Optional.of(savedUser));

        // WHEN / THEN
        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> authService.login(loginRequest));

        assertEquals("Account is suspended. Contact support.", ex.getMessage());
    }
}
