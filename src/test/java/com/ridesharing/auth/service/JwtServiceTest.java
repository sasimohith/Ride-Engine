package com.ridesharing.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for JwtService — token generation and validation.
 *
 * No Spring context needed — JwtService is a plain class with no DB dependency.
 * We use ReflectionTestUtils to set the private @Value fields directly.
 *
 * Happy path: generate token → extract claims → all correct
 * Failure 1: expired token → isTokenValid returns false
 * Failure 2: tampered token → isTokenValid returns false
 * Failure 3: random garbage string → isTokenValid returns false
 * Edge case: extract userId and role from a valid token
 */
@DisplayName("JwtService Tests")
class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();

        // Inject @Value fields without starting Spring context
        ReflectionTestUtils.setField(jwtService, "secretKey",
                "myTestSecretKeyThatIsAtLeast32CharactersLongForHS256");
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiry", 900000L);
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpiry", 604800000L);
    }

    @Test
    @DisplayName("should_generateValidAccessToken_when_calledWithUserIdAndRole")
    void should_generateValidAccessToken_when_calledWithUserIdAndRole() {
        // WHEN
        String token = jwtService.generateAccessToken(42L, "RIDER");

        // THEN
        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(token.contains(".")); // JWT has 3 parts separated by dots
    }

    @Test
    @DisplayName("should_extractCorrectUserId_when_tokenIsValid")
    void should_extractCorrectUserId_when_tokenIsValid() {
        // GIVEN
        String token = jwtService.generateAccessToken(42L, "RIDER");

        // WHEN
        Long userId = jwtService.extractUserId(token);

        // THEN
        assertEquals(42L, userId);
    }

    @Test
    @DisplayName("should_extractCorrectRole_when_tokenIsValid")
    void should_extractCorrectRole_when_tokenIsValid() {
        // GIVEN
        String token = jwtService.generateAccessToken(42L, "DRIVER");

        // WHEN
        String role = jwtService.extractRole(token);

        // THEN
        assertEquals("DRIVER", role);
    }

    @Test
    @DisplayName("should_returnTrue_when_tokenIsValid")
    void should_returnTrue_when_tokenIsValid() {
        // GIVEN
        String token = jwtService.generateAccessToken(1L, "RIDER");

        // WHEN / THEN
        assertTrue(jwtService.isTokenValid(token));
    }

    @Test
    @DisplayName("should_returnFalse_when_tokenIsTampered")
    void should_returnFalse_when_tokenIsTampered() {
        // GIVEN: a valid token with one character changed (tampered)
        String token = jwtService.generateAccessToken(1L, "RIDER");
        String tamperedToken = token.substring(0, token.length() - 1) + "X";

        // WHEN / THEN
        assertFalse(jwtService.isTokenValid(tamperedToken));
    }

    @Test
    @DisplayName("should_returnFalse_when_tokenIsRandomGarbage")
    void should_returnFalse_when_tokenIsRandomGarbage() {
        // WHEN / THEN
        assertFalse(jwtService.isTokenValid("this.is.not.a.jwt"));
    }

    @Test
    @DisplayName("should_returnFalse_when_tokenIsExpired")
    void should_returnFalse_when_tokenIsExpired() {
        // GIVEN: set expiry to 0ms (token expires immediately)
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiry", 0L);
        String token = jwtService.generateAccessToken(1L, "RIDER");

        // WHEN / THEN: token is already expired
        assertFalse(jwtService.isTokenValid(token));
    }

    @Test
    @DisplayName("should_generateDifferentTokens_when_accessVsRefresh")
    void should_generateDifferentTokens_when_accessVsRefresh() {
        // GIVEN
        String accessToken = jwtService.generateAccessToken(42L, "RIDER");
        String refreshToken = jwtService.generateRefreshToken(42L, "RIDER");

        // THEN: they should be different (different expiry times)
        assertFalse(accessToken.equals(refreshToken));
    }
}
