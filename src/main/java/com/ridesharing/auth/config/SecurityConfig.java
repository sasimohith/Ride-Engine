package com.ridesharing.auth.config;

import com.ridesharing.auth.filter.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configures Spring Security for the entire application.
 *
 * Key decisions:
 *   1. STATELESS sessions — no server-side session storage (JWT handles auth)
 *   2. CSRF disabled — not needed for stateless REST APIs (CSRF is for cookie-based auth)
 *   3. Public endpoints — /api/auth/** doesn't need a token (login, register)
 *   4. Role-based access — ADMIN endpoints only for ADMIN role
 *   5. JWT filter — our JwtAuthenticationFilter runs before Spring's default auth filter
 *
 * Filter chain order (what happens to every request):
 *   Request → JwtAuthenticationFilter → Spring's auth filters → SecurityConfig rules → Controller
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * Defines the security filter chain — the rules for who can access what.
     *
     * @param http the HttpSecurity builder
     * @return the configured SecurityFilterChain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF — not needed for stateless JWT-based REST APIs.
                // CSRF protection is for browser cookie-based sessions.
                // Since we use JWT in the Authorization header, CSRF doesn't apply.
                .csrf(csrf -> csrf.disable())

                // Set session management to STATELESS — no HttpSession created.
                // Every request is authenticated independently via JWT.
                // This is what makes our app horizontally scalable —
                // any server instance can handle any request.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Define endpoint access rules (ORDER MATTERS — first match wins)
                .authorizeHttpRequests(auth -> auth

                        // PUBLIC: auth endpoints (register, login) — no token needed
                        .requestMatchers("/api/auth/**").permitAll()

                        // PUBLIC: actuator endpoints (health, metrics) — for monitoring
                        .requestMatchers("/actuator/**").permitAll()

                        // PUBLIC: WebSocket endpoints — auth handled at WebSocket level
                        .requestMatchers("/ws/**").permitAll()

                        // ADMIN ONLY: admin management + payment refunds
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/payments/*/refund").hasRole("ADMIN")

                        // AUTHENTICATED: everything else requires a valid JWT
                        .anyRequest().authenticated()
                )

                // Insert our JWT filter BEFORE Spring's UsernamePasswordAuthenticationFilter.
                // This means our filter validates the JWT first, THEN Spring checks the rules above.
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * The password encoder bean used throughout the application.
     * BCrypt is the industry standard for password hashing:
     *   - Automatically adds a random salt (two identical passwords produce different hashes)
     *   - Deliberately slow (prevents brute-force attacks)
     *   - Configurable strength (default 10 rounds = good balance of security and speed)
     *
     * @return BCryptPasswordEncoder instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
