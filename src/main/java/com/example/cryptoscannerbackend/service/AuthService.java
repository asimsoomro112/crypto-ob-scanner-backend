package com.example.cryptoscannerbackend.service;

import com.example.cryptoscannerbackend.model.ERole;
import com.example.cryptoscannerbackend.model.User;
import com.example.cryptoscannerbackend.repository.UserRepository;
import com.example.cryptoscannerbackend.security.jwt.JwtUtils;
import com.example.cryptoscannerbackend.security.services.UserDetailsImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {
    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    JwtUtils jwtUtils;

    public User registerUser(String username, String email, String password, String plan) {
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("Error: Username is already taken!");
        }
        if (userRepository.existsByEmail(email)) {
            throw new RuntimeException("Error: Email is already in use!");
        }

        User user = new User(username, email, encoder.encode(password));
        Set<ERole> roles = user.getRoles(); // Get the default ROLE_USER added in constructor

        // Handle plan-based role assignment
        if ("trial".equalsIgnoreCase(plan)) {
            roles.add(ERole.ROLE_TRIAL);
            user.setTrialStartDate(LocalDateTime.now());
            user.setTrialEndDate(LocalDateTime.now().plusDays(3)); // 3-day trial
            user.setPremium(false);
        } else if ("premium".equalsIgnoreCase(plan)) { // For direct premium sign-up if you add that later
            roles.add(ERole.ROLE_PREMIUM);
            user.setPremium(true);
        } else {
            // Default to basic user if no specific plan is provided
            user.setPremium(false);
        }
        user.setRoles(roles); // Ensure roles are set back

        return userRepository.save(user);
    }

    public String authenticateUser(String username, String password) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        return jwtUtils.generateJwtToken(authentication);
    }
}
