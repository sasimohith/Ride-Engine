package com.ridesharing.auth.controller;

import com.ridesharing.auth.dto.AuthResponse;
import com.ridesharing.auth.dto.LoginRequest;
import com.ridesharing.auth.dto.RegisterRequest;
import com.ridesharing.auth.service.AuthService;
import com.ridesharing.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for authentication endpoints.
 *
 * Endpoints:
 *   POST /api/auth/register — Create a new user account
 *   POST /api/auth/login    — Authenticate and get JWT tokens
 *
 * This controller does HTTP handling ONLY:
 *   - Receives the request
 *   - Validates the request body (@Valid)
 *   - Delegates to AuthService for business logic
 *   - Wraps the result in ApiResponse and returns it
 *
 * NO business logic here. Ever. That's the rule.
 *
 * @RestController = @Controller + @ResponseBody (returns JSON, not HTML)
 * @RequestMapping("/api/auth") = all endpoints in this class start with /api/auth
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Registers a new user (Rider or Driver).
     *
     * @param request the registration data (validated automatically by @Valid)
     * @return 201 Created with JWT tokens
     *
     * Example request:
     *   POST /api/auth/register
     *   {
     *     "name": "Raj Kumar",
     *     "email": "raj@example.com",
     *     "password": "securePassword123",
     *     "phone": "9876543210",
     *     "role": "RIDER"
     *   }
     *
     * Example response:
     *   201 Created
     *   {
     *     "success": true,
     *     "message": "Registration successful",
     *     "data": {
     *       "accessToken": "eyJhbGci...",
     *       "refreshToken": "eyJhbGci...",
     *       "role": "RIDER"
     *     }
     *   }
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Register request received for email: {}", request.getEmail());
        AuthResponse authResponse = authService.register(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful", authResponse));
    }

    /**
     * Authenticates a user and returns JWT tokens.
     *
     * @param request the login credentials (validated automatically by @Valid)
     * @return 200 OK with JWT tokens
     *
     * Example request:
     *   POST /api/auth/login
     *   {
     *     "email": "raj@example.com",
     *     "password": "securePassword123"
     *   }
     *
     * Example response:
     *   200 OK
     *   {
     *     "success": true,
     *     "message": "Login successful",
     *     "data": {
     *       "accessToken": "eyJhbGci...",
     *       "refreshToken": "eyJhbGci...",
     *       "role": "RIDER"
     *     }
     *   }
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login request received for email: {}", request.getEmail());
        AuthResponse authResponse = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", authResponse));
    }
}
