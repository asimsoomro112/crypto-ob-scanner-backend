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
        userService.grantPremiumAccess(request.getUserId()); // Ensure grantPremiumAccess takes String username or Long userId
        return ResponseEntity.ok(new MessageResponse("Transaction hash submitted! Your access will be activated shortly after verification."));
    }

    // DTOs for PaymentController
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PaymentProofRequest {
        private String transactionHash;
        private String userId; // Changed to String to match frontend for now, or Long if you send user ID as Long
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MessageResponse {
        private String message;
    }
}
