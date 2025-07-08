package com.example.cryptoscannerbackend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

// IMPORTANT: Add this import for ERole
import com.example.cryptoscannerbackend.model.ERole;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @ElementCollection(targetClass = ERole.class, fetch = FetchType.EAGER) // Corrected: removed 'main.'
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Set<ERole> roles = new HashSet<>(); // Corrected: removed 'main.'

    private LocalDateTime trialStartDate;
    private LocalDateTime trialEndDate;
    private boolean isPremium;

    public User(String username, String email, String password) {
        this.username = username;
        this.email = email;
        this.password = password;
        this.roles.add(ERole.ROLE_USER); // Corrected: removed 'main.'
    }
}
