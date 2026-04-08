package com.ridesharing.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

/**
 * Handles ALL JWT operations for the application:
 *   - Generate access tokens (short-lived: 15 minutes)
 *   - Generate refresh tokens (long-lived: 7 days)
 *   - Validate tokens (not expired, not tampered)
 *   - Extract claims (userId, role) from tokens
 *
 * A JWT looks like: xxxxx.yyyyy.zzzzz
 *   Part 1 (xxxxx): Header — {"alg": "HS256", "typ": "JWT"}
 *   Part 2 (yyyyy): Payload — {"userId": 42, "role": "RIDER", "exp": 1711680000}
 *   Part 3 (zzzzz): Signature — HMAC-SHA256(part1 + "." + part2, SECRET_KEY)
 *
 * The signature ensures nobody can tamper with the payload.
 * If they change "RIDER" to "ADMIN", the signature won't match and we reject it.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    // Secret key for signing tokens — loaded from application.yml via environment variable.
    // NEVER hardcode this. In prod, set JWT_SECRET as an environment variable.
    @Value("${jwt.secret:myDefaultSecretKeyThatIsAtLeast32CharactersLongForHS256Algorithm}")
    private String secretKey;

    // Access token expiry: 15 minutes (in milliseconds).
    // Short-lived because if stolen, the damage window is only 15 minutes.
    @Value("${jwt.access-token-expiry:900000}")
    private long accessTokenExpiry;

    // Refresh token expiry: 7 days (in milliseconds).
    // Used to get a new access token without re-entering password.
    @Value("${jwt.refresh-token-expiry:604800000}")
    private long refreshTokenExpiry;

    /**
     * Generates a short-lived access token for a user.
     * This token is sent with every API request in the Authorization header.
     *
     * @param userId the user's database ID
     * @param role the user's role (RIDER, DRIVER, ADMIN)
     * @return signed JWT string
     */
    public String generateAccessToken(Long userId, String role) {
        return buildToken(userId, role, accessTokenExpiry);
    }

    /**
     * Generates a long-lived refresh token for a user.
     * When the access token expires, the client sends this to get a new access token
     * without requiring the user to log in again.
     *
     * @param userId the user's database ID
     * @param role the user's role (RIDER, DRIVER, ADMIN)
     * @return signed JWT string
     */
    public String generateRefreshToken(Long userId, String role) {
        return buildToken(userId, role, refreshTokenExpiry);
    }

    /**
     * Extracts the user ID from a token.
     * The "subject" claim in JWT is the user ID (stored as a String, parsed to Long).
     *
     * @param token the JWT string
     * @return the user ID
     */
    public Long extractUserId(String token) {
        String subject = extractClaim(token, Claims::getSubject);
        return Long.parseLong(subject);
    }

    /**
     * Extracts the user role from a token.
     * We store the role as a custom claim called "role".
     *
     * @param token the JWT string
     * @return the role string (e.g., "RIDER")
     */
    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    /**
     * Validates whether a token is legitimate and not expired.
     * This is called on EVERY request by JwtAuthenticationFilter.
     *
     * @param token the JWT string from the Authorization header
     * @return true if token is valid and not expired
     */
    public boolean isTokenValid(String token) {
        try {
            extractAllClaims(token);
            return true;
        } catch (ExpiredJwtException ex) {
            log.warn("JWT token expired: {}", ex.getMessage());
            return false;
        } catch (MalformedJwtException ex) {
            log.warn("JWT token malformed: {}", ex.getMessage());
            return false;
        } catch (SignatureException ex) {
            log.warn("JWT signature invalid: {}", ex.getMessage());
            return false;
        } catch (Exception ex) {
            log.warn("JWT token invalid: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Builds a JWT token with the given claims and expiry.
     * This is the core method — both access and refresh tokens use it.
     *
     * @param userId user's database ID (stored as the "subject" claim)
     * @param role user's role (stored as the "role" custom claim)
     * @param expiryMs how long until the token expires (in milliseconds)
     * @return signed JWT string
     */
    private String buildToken(Long userId, String role, long expiryMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiryMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Extracts a specific claim from the token using a resolver function.
     * This is a generic helper — extractUserId and extractRole both use it.
     *
     * @param token the JWT string
     * @param claimsResolver function that picks the claim you want
     * @return the extracted claim value
     */
    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Parses the token and extracts ALL claims (payload data).
     * If the token is expired, tampered, or malformed, this throws an exception.
     * The signing key is used to verify the signature hasn't been tampered with.
     *
     * @param token the JWT string
     * @return all claims from the token payload
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Creates the cryptographic signing key from our secret string.
     * HS256 requires a key of at least 256 bits (32 characters).
     *
     * @return SecretKey used for signing and verifying tokens
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
