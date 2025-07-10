package com.example.cryptoscannerbackend.service;

import com.example.cryptoscannerbackend.model.ERole;
import com.example.cryptoscannerbackend.model.User;
import com.example.cryptoscannerbackend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List; // Import List
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public Map<String, Object> getUserStatus(String username) {
        Optional<User> userOptional = userRepository.findByUsername(username);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            Map<String, Object> status = new HashMap<>();
            status.put("username", user.getUsername());
            status.put("isPremium", user.isPremium());
            boolean trialActive = user.getTrialEndDate() != null && user.getTrialEndDate().isAfter(LocalDateTime.now());
            status.put("trialActive", trialActive);
            status.put("trialExpiryDate", user.getTrialEndDate() != null ? user.getTrialEndDate().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
            status.put("roles", user.getRoles());
            return status;
        }
        return new HashMap<>();
    }

    public boolean grantPremium(String username) {
        Optional<User> userOptional = userRepository.findByUsername(username);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            user.setPremium(true);
            user.setTrialStartDate(null);
            user.setTrialEndDate(null);

            Set<ERole> roles = user.getRoles();
            roles.add(ERole.ROLE_PREMIUM);
            roles.remove(ERole.ROLE_TRIAL);
            roles.remove(ERole.ROLE_USER);
            user.setRoles(roles);

            userRepository.save(user);
            System.out.println("Granted premium access to user: " + username);
            return true;
        }
        return false;
    }

    public boolean revokePremium(String username) {
        Optional<User> userOptional = userRepository.findByUsername(username);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            user.setPremium(false);
            user.setTrialStartDate(null);
            user.setTrialEndDate(null);

            Set<ERole> roles = user.getRoles();
            roles.remove(ERole.ROLE_PREMIUM);
            roles.add(ERole.ROLE_USER);
            user.setRoles(roles);

            userRepository.save(user);
            System.out.println("Revoked premium access from user: " + username);
            return true;
        }
        return false;
    }

    public boolean activateTrial(String username, int trialDays) {
        Optional<User> userOptional = userRepository.findByUsername(username);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            user.setTrialStartDate(LocalDateTime.now());
            user.setTrialEndDate(LocalDateTime.now().plusDays(trialDays));
            user.setPremium(false);

            Set<ERole> roles = user.getRoles();
            roles.add(ERole.ROLE_TRIAL);
            roles.remove(ERole.ROLE_USER);
            roles.remove(ERole.ROLE_PREMIUM);
            user.setRoles(roles);

            userRepository.save(user);
            System.out.println("Activated " + trialDays + "-day trial for user: " + username);
            return true;
        }
        return false;
    }

    // NEW METHOD: Get all users from the repository
    public List<User> getAllUsers() {
        return userRepository.findAll(); // JpaRepository provides findAll()
    }
}
