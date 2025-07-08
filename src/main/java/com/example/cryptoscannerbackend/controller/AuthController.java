package com.example.cryptoscannerbackend.controller;

import com.example.cryptoscannerbackend.model.ERole;
import com.example.cryptoscannerbackend.model.User;
import com.example.cryptoscannerbackend.repository.UserRepository;
import com.example.cryptoscannerbackend.security.jwt.JwtUtils;
import com.example.cryptoscannerbackend.security.services.UserDetailsImpl;
import com.example.cryptoscannerbackend.service.AuthService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*", maxAge = 3600) // Temporarily allow all origins for local testing, change to "https://ccscanner.netlify.app" for production
public class AuthController {
    @Autowired
    AuthService authService; // Use the service

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody RegisterRequest registerRequest) {
        try {
            User registeredUser = authService.registerUser(
                    registerRequest.getUsername(),
                    registerRequest.getEmail(),
                    registerRequest.getPassword(),
                    registerRequest.getPlan()
            );
            return ResponseEntity.ok(new MessageResponse("User registered successfully! User ID: " + registeredUser.getId()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(e.getMessage()));
        }
    }

    @PostMapping("/login") // Changed from /signin to /login to match frontend
    public ResponseEntity<?> authenticateUser(@RequestBody LoginRequest loginRequest) {
        try {
            String jwt = authService.authenticateUser(loginRequest.getUsername(), loginRequest.getPassword());
            UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            List<String> roles = userDetails.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(new JwtResponse(jwt, userDetails.getId(), userDetails.getUsername(), userDetails.getEmail(), roles));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("Invalid credentials: " + e.getMessage()));
        }
    }

    // DTOs for AuthController
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RegisterRequest {
        private String username;
        private String email;
        private String password;
        private String plan;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MessageResponse {
        private String message;
    }

    @Data @NoArgsConstructor
    public static class JwtResponse {
        private String token;
        private String type = "Bearer";
        private Long id;
        private String username;
        private String email;
        private List<String> roles;

        public JwtResponse(String accessToken, Long id, String username, String email, List<String> roles) {
            this.token = accessToken;
            this.id = id;
            this.username = username;
            this.email = email;
            this.roles = roles;
        }
    }
}
