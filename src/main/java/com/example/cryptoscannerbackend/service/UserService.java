package com.example.cryptoscannerbackend.service;

import com.example.cryptoscannerbackend.model.ERole;
import com.example.cryptoscannerbackend.model.User;
import com.example.cryptoscannerbackend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class UserService {
    @Autowired
    UserRepository userRepository;

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Transactional
    public User save(User user) {
        return userRepository.save(user);
    }

    @Transactional
    public Map<String, Object> getUserStatus(String username) {
        Map<String, Object> status = new HashMap<>();
        Optional<User> userOptional = userRepository.findByUsername(username);

        if (userOptional.isPresent()) {
            User user = userOptional.get();
            status.put("username", user.getUsername());
            status.put("isPremium", user.isPremium());

            boolean trialActive = false;
            String trialExpiry = null;

            if (user.getRoles().contains(ERole.ROLE_TRIAL) && user.getTrialEndDate() != null) {
                if (LocalDateTime.now().isBefore(user.getTrialEndDate())) {
                    trialActive = true;
                    trialExpiry = user.getTrialEndDate().toString();
                } else {
                    // Trial expired, remove ROLE_TRIAL
                    user.getRoles().remove(ERole.ROLE_TRIAL);
                    userRepository.save(user); // Persist the role change
                }
            }
            status.put("trialActive", trialActive);
            status.put("trialExpiryDate", trialExpiry);
            status.put("roles", user.getRoles().stream().map(Enum::name).collect(Collectors.toSet()));

        } else {
            status.put("error", "User not found.");
        }
        return status;
    }

    @Transactional
    public void grantPremiumAccess(String username) {
        Optional<User> userOptional = userRepository.findByUsername(username);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            user.setPremium(true);
            user.getRoles().add(ERole.ROLE_PREMIUM);
            user.getRoles().remove(ERole.ROLE_TRIAL); // Remove trial role if upgrading
            userRepository.save(user);
            System.out.println("User " + username + " granted premium access.");
        } else {
            System.err.println("User " + username + " not found for premium access grant.");
        }
    }
}
