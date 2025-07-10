package com.example.cryptoscannerbackend.security;

import com.example.cryptoscannerbackend.security.jwt.AuthTokenFilter;
import com.example.cryptoscannerbackend.security.services.UserDetailsServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer; // NEW IMPORT
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.MvcRequestMatcher;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfig {

    @Autowired
    UserDetailsServiceImpl userDetailsService;

    @Autowired
    private AuthTokenFilter authTokenFilter;

    @Autowired
    private HandlerMappingIntrospector introspector;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    // NEW BEAN: This completely bypasses the security filter chain for the specified paths
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().requestMatchers(new MvcRequestMatcher.Builder(introspector).pattern("/h2-console/**"));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Create an MvcRequestMatcher.Builder
        MvcRequestMatcher.Builder mvcMatcherBuilder = new MvcRequestMatcher.Builder(introspector);

        http.cors(Customizer.withDefaults())
                // Keep CSRF disabled globally for API, or specifically ignore if needed
                .csrf(AbstractHttpConfigurer::disable) // Revert to global disable for simplicity, or keep targeted if you know why it's needed
                // If you want to keep targeted CSRF, use:
                // .csrf(csrf -> csrf.ignoringRequestMatchers(mvcMatcherBuilder.pattern("/h2-console/**")))


                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(mvcMatcherBuilder.pattern("/api/auth/**")).permitAll()
                        .requestMatchers(mvcMatcherBuilder.pattern("/api/health")).permitAll()
                        // REMOVED: .requestMatchers(mvcMatcherBuilder.pattern("/h2-console/**")).permitAll()
                        // This is now handled by webSecurityCustomizer() above, which is stronger
                        .requestMatchers(mvcMatcherBuilder.pattern("/api/scan-order-blocks")).hasAnyRole("TRIAL", "PREMIUM")
                        .requestMatchers(mvcMatcherBuilder.pattern("/api/user/status")).authenticated()
                        .requestMatchers(mvcMatcherBuilder.pattern("/api/payment/submit-proof")).authenticated()
                        .requestMatchers(mvcMatcherBuilder.pattern("/api/admin/**")).hasRole("ADMIN")
                        .anyRequest().authenticated()
                );

        // Frame options for H2 Console (crucial if it runs in an iframe)
        http.headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()));

        http.addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
