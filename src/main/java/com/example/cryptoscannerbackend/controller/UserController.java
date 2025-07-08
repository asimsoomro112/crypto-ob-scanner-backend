package com.example.cryptoscannerbackend.controller;

import com.example.cryptoscannerbackend.security.services.UserDetailsImpl;
import com.example.cryptoscannerbackend.service.UserService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "*", maxAge = 3600) // Temporarily allow all origins for local testing, change to "https://ccscanner.netlify.app" for production
public class UserController {
    @Autowired
    UserService userService;

    @GetMapping("/status")
    public ResponseEntity<?> getUserStatus() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal().equals("anonymousUser")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("User not authenticated."));
        }

        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        Map<String, Object> status = userService.getUserStatus(userDetails.getUsername());
        return ResponseEntity.ok(status);
    }

    // DTO for MessageResponse (can be shared or defined here)
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MessageResponse {
        private String message;
    }
}
