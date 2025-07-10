package com.example.cryptoscannerbackend.service;

import com.example.cryptoscannerbackend.model.ERole;
import com.example.cryptoscannerbackend.model.User;
import com.example.cryptoscannerbackend.repository.UserRepository;
import com.example.cryptoscannerbackend.security.jwt.JwtUtils;
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

    @Autowired
    private UserService userService;

    public User registerUser(String username, String email, String password, String plan) {
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("Error: Username is already taken!");
        }
        if (userRepository.existsByEmail(email)) {
            throw new RuntimeException("Error: Email is already in use!");
        }

        User user = new User(username, email, encoder.encode(password));

        if ("trial".equalsIgnoreCase(plan)) {
            user = userRepository.save(user);
            userService.activateTrial(user.getUsername(), 3);
        } else if ("premium".equalsIgnoreCase(plan)) {
            user.setPremium(false);
            userRepository.save(user);
        } else {
            user.setPremium(false);
            userRepository.save(user);
        }
        return user;
    }

    // TEMPORARY METHOD FOR ADMIN REGISTRATION - REMOVE AFTER FIRST ADMIN IS CREATED

        public User registerAdmin(String username, String email, String password) {
            if (userRepository.existsByUsername(username)) {
                throw new RuntimeException("Error: Username is already taken!");
            }
            if (userRepository.existsByEmail(email)) {
                throw new RuntimeException("Error: Email is already in use!");
            }

            User adminUser = new User(username, email, encoder.encode(password));
            Set<ERole> roles = adminUser.getRoles();
            roles.add(ERole.ROLE_ADMIN);
            adminUser.setRoles(roles);
            adminUser.setPremium(true);
            adminUser.setTrialStartDate(null);
            adminUser.setTrialEndDate(null);

            return userRepository.save(adminUser);
        }


    public String authenticateUser(String username, String password) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        return jwtUtils.generateJwtToken(authentication);
    }
}
    