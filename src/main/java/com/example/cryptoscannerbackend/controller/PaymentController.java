package com.example.cryptoscannerbackend.controller;

import com.example.cryptoscannerbackend.service.UserService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment")
@CrossOrigin(origins = "*", maxAge = 3600) // Temporarily allow all origins for local testing, change to "https://ccscanner.netlify.app" for production
public class PaymentController {
    @Autowired
    UserService userService;

    @PostMapping("/submit-proof")
    public ResponseEntity<?> submitPaymentProof(@RequestBody PaymentProofRequest request) {
        // In a real application, you'd verify the transaction hash with a payment gateway.
        // For now, we'll just grant premium access directly.
        System.out.println("Received payment proof for user ID: " + request.getUserId() + " with TxID: " + request.getTransactionHash());

        // Assuming request.getUserId() actually contains the username for now.
        // If it's a numeric ID, you'll need a userService.grantPremium(Long userId) method
        // or to fetch the username from the ID first.
        boolean success = userService.grantPremium(request.getUserId()); // Corrected method call

        if (success) {
            return ResponseEntity.ok(new MessageResponse("Transaction hash submitted! Your access will be activated shortly after verification."));
        } else {
            return ResponseEntity.badRequest().body(new MessageResponse("Failed to process payment proof. User not found or update failed."));
        }
    }

    // DTOs for PaymentController
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PaymentProofRequest {
        private String transactionHash;
        private String userId; // Changed to String to match frontend for now, or Long if you send user ID as Long
        // IMPORTANT: If this is a numeric ID, userService.grantPremium needs adjustment
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MessageResponse {
        private String message;
    }
}
