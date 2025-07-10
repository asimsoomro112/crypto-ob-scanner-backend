package com.example.cryptoscannerbackend.controller;

import com.example.cryptoscannerbackend.service.UserService;
import com.example.cryptoscannerbackend.security.services.UserDetailsImpl;
import com.example.cryptoscannerbackend.model.User; // Import User entity
import lombok.AllArgsConstructor; // For DTO
import lombok.Data; // For DTO
import lombok.NoArgsConstructor; // For DTO
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List; // Import List
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors; // Import Collectors

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AdminController {

    @Autowired
    private UserService userService;

    // Existing methods (grantPremiumAccess, revokePremiumAccess, activateTrialAccess) go here...

    @PostMapping("/grant-premium/{username}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> grantPremiumAccess(@PathVariable String username, Authentication authentication) {
        UserDetailsImpl adminDetails = (UserDetailsImpl) authentication.getPrincipal();
        System.out.println("Admin " + adminDetails.getUsername() + " is attempting to grant premium to: " + username);

        try {
            boolean success = userService.grantPremium(username);

            if (success) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "Premium access granted to user: " + username);
                return ResponseEntity.ok(response);
            } else {
                Map<String, String> response = new HashMap<>();
                response.put("message", "User not found or premium status could not be updated for: " + username);
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            System.err.println("Error granting premium access to " + username + ": " + e.getMessage());
            Map<String, String> response = new HashMap<>();
            response.put("message", "An error occurred while granting premium access.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/revoke-premium/{username}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> revokePremiumAccess(@PathVariable String username, Authentication authentication) {
        UserDetailsImpl adminDetails = (UserDetailsImpl) authentication.getPrincipal();
        System.out.println("Admin " + adminDetails.getUsername() + " is attempting to revoke premium from: " + username);

        try {
            boolean success = userService.revokePremium(username);
            if (success) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "Premium access revoked from user: " + username);
                return ResponseEntity.ok(response);
            } else {
                Map<String, String> response = new HashMap<>();
                response.put("message", "User not found or premium status could not be revoked for: " + username);
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            System.err.println("Error revoking premium access from " + username + ": " + e.getMessage());
            Map<String, String> response = new HashMap<>();
            response.put("message", "An error occurred while revoking premium access.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/activate-trial/{username}/{days}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> activateTrialAccess(@PathVariable String username, @PathVariable int days, Authentication authentication) {
        UserDetailsImpl adminDetails = (UserDetailsImpl) authentication.getPrincipal();
        System.out.println("Admin " + adminDetails.getUsername() + " is attempting to activate " + days + "-day trial for: " + username);

        try {
            boolean success = userService.activateTrial(username, days);
            if (success) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "Trial access activated for user: " + username + " for " + days + " days.");
                return ResponseEntity.ok(response);
            } else {
                Map<String, String> response = new HashMap<>();
                response.put("message", "User not found or trial could not be activated for: " + username);
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            System.err.println("Error activating trial access for " + username + ": " + e.getMessage());
            Map<String, String> response = new HashMap<>();
            response.put("message", "An error occurred while activating trial access.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // NEW ENDPOINT: Get all users for admin dashboard
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserDTO>> getAllUsers() {
        List<User> users = userService.getAllUsers(); // Call the new method in UserService
        // Convert User entities to UserDTOs to avoid exposing sensitive data like hashed passwords
        List<UserDTO> userDTOs = users.stream()
                .map(UserDTO::new) // Use the new DTO constructor
                .collect(Collectors.toList());
        return ResponseEntity.ok(userDTOs);
    }

    // NEW DTO: To safely expose user data to the frontend
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UserDTO {
        private Long id;
        private String username;
        private String email;
        private boolean isPremium;
        private boolean trialActive; // Derived from trialEndDate
        private Long trialExpiryDate; // Epoch milliseconds for frontend
        private Set<String> roles;

        public UserDTO(User user) {
            this.id = user.getId();
            this.username = user.getUsername();
            this.email = user.getEmail();
            this.isPremium = user.isPremium();
            this.trialActive = user.getTrialEndDate() != null && user.getTrialEndDate().isAfter(LocalDateTime.now());
            this.trialExpiryDate = user.getTrialEndDate() != null ? user.getTrialEndDate().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
            this.roles = user.getRoles().stream().map(Enum::name).collect(Collectors.toSet());
        }
    }
}
