package com.example.cryptoscannerbackend.security.jwt;

import com.example.cryptoscannerbackend.security.services.UserDetailsImpl;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException; // Keep this import
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64; // Import Base64
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtUtils {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms}")
    private int jwtExpirationMs;

    private Key key;

    @PostConstruct
    public void init() {
        // Ensure the secret is at least 32 bytes (256 bits) for HS256, 64 bytes (512 bits) for HS512
        // It's best practice to use a base64 encoded secret directly.
        // If the secret is too short, Keys.hmacShaKeyFor will throw an IllegalArgumentException.
        try {
            // Decode the secret from Base64 if it's provided as such, or use raw bytes
            // It's safer to provide a Base64 encoded string as the secret in environment variables
            // For HS512, a key of 64 bytes (512 bits) is required.
            // A Base64 encoded string of 64 random bytes would be ~86 characters long.
            if (jwtSecret == null || jwtSecret.isEmpty()) {
                System.err.println("ERROR: JWT secret is not configured. Generating a temporary insecure key.");
                this.key = Keys.secretKeyFor(SignatureAlgorithm.HS512); // Fallback for missing secret
            } else {
                // Attempt to decode as Base64 first. If it fails, use raw bytes.
                byte[] decodedKey;
                try {
                    decodedKey = Base64.getDecoder().decode(jwtSecret);
                    // Ensure decoded key is long enough for HS512 (64 bytes)
                    if (decodedKey.length < 64) {
                        System.err.println("WARNING: Decoded JWT secret is too short for HS512 (" + decodedKey.length + " bytes). Generating a new secure random key.");
                        this.key = Keys.secretKeyFor(SignatureAlgorithm.HS512);
                    } else {
                        this.key = Keys.hmacShaKeyFor(decodedKey);
                    }
                } catch (IllegalArgumentException e) {
                    // Not a valid Base64 string, treat as raw string bytes
                    System.err.println("WARNING: JWT secret is not Base64 encoded. Using raw bytes. Ensure it's long enough for HS512 (at least 64 characters).");
                    if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 64) {
                        System.err.println("WARNING: Raw JWT secret is too short for HS512 (" + jwtSecret.getBytes(StandardCharsets.UTF_8).length + " bytes). Generating a new secure random key.");
                        this.key = Keys.secretKeyFor(SignatureAlgorithm.HS512);
                    } else {
                        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("ERROR: Failed to initialize JWT key. Using a temporary, insecure key. Error: " + e.getMessage());
            this.key = Keys.secretKeyFor(SignatureAlgorithm.HS512); // Ultimate fallback
        }
    }

    public String generateJwtToken(Authentication authentication) {
        UserDetailsImpl userPrincipal = (UserDetailsImpl) authentication.getPrincipal();
        List<String> roles = userPrincipal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        return Jwts.builder()
                .subject(userPrincipal.getUsername())
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(key)
                .compact();
    }

    public String getUserNameFromJwtToken(String token) {
        // Use the same parser builder pattern as validateJwtToken for consistency
        Claims claims = Jwts.parserBuilder() // Use parserBuilder()
                .setSigningKey(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public List<String> getRolesFromJwtToken(String token) {
        // Use the same parser builder pattern as validateJwtToken for consistency
        Claims claims = Jwts.parserBuilder() // Use parserBuilder()
                .setSigningKey(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("roles", List.class);
    }

    public boolean validateJwtToken(String authToken) {
        try {
            // Use Jwts.parserBuilder() for modern JJWT versions
            Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseSignedClaims(authToken);
            return true;
        } catch (SecurityException e) {
            System.err.println("Invalid JWT signature: " + e.getMessage());
        } catch (MalformedJwtException e) {
            System.err.println("Invalid JWT token: " + e.getMessage());
        } catch (ExpiredJwtException e) {
            System.err.println("JWT token is expired: " + e.getMessage());
        } catch (UnsupportedJwtException e) {
            System.err.println("JWT token is unsupported: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.err.println("JWT claims string is empty: " + e.getMessage());
        }
        return false;
    }
}
