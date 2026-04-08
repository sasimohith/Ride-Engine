package com.ridesharing.auth.filter;

import com.ridesharing.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Intercepts EVERY HTTP request before it reaches any controller.
 * This is where JWT validation happens.
 *
 * Flow for every request:
 *   1. Check if Authorization header exists and starts with "Bearer "
 *   2. If no → skip this filter, let Spring Security handle (will return 401 for protected endpoints)
 *   3. If yes → extract the token, validate it using JwtService
 *   4. If valid → extract userId and role, tell Spring Security "this user is authenticated"
 *   5. If invalid → skip, Spring Security will reject the request
 *
 * OncePerRequestFilter guarantees this filter runs EXACTLY ONCE per request
 * (not multiple times if the request is forwarded internally).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * The core filter logic — runs for every HTTP request.
     *
     * @param request the incoming HTTP request
     * @param response the outgoing HTTP response
     * @param filterChain the chain of filters — we call filterChain.doFilter() to pass to the next filter
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Step 1: Get the Authorization header
        String authHeader = request.getHeader("Authorization");

        // Step 2: If no token or doesn't start with "Bearer ", skip this filter
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Step 3: Extract the token (remove "Bearer " prefix)
        String token = authHeader.substring(7);

        // Step 4: Validate the token
        if (jwtService.isTokenValid(token)) {
            // Step 5: Extract user info from token
            Long userId = jwtService.extractUserId(token);
            String role = jwtService.extractRole(token);

            // Step 6: Create Spring Security authentication object
            // "ROLE_" prefix is a Spring Security convention for role-based access
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );

            // Step 7: Set authentication in the SecurityContext
            // After this line, Spring Security considers this request "authenticated"
            // Any controller can now call SecurityContextHolder to get the userId and role
            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("Authenticated userId={} with role={} for path={}",
                    userId, role, request.getRequestURI());
        }

        // Step 8: Continue to the next filter (and eventually the controller)
        filterChain.doFilter(request, response);
    }
}
