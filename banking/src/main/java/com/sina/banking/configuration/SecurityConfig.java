package com.sina.banking.configuration;

import com.sina.banking.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.sina.banking.security.AppUserDetailsService;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final AppUserDetailsService appUserDetailsService;
    private final JwtAuthenticationFilter jwtAuthFilter;

    public SecurityConfig(AppUserDetailsService appUserDetailsService, JwtAuthenticationFilter jwtAuthFilter) {
        this.appUserDetailsService = appUserDetailsService;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(appUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

     @Bean
     public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
         return configuration.getAuthenticationManager();
     }

     @Bean
     public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        return  httpSecurity
                // No cookies/sessions involved - the JWT itself is the proof on every request,
                // so CSRF protection (which exists to protect cookie-based auth) doesn't apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth ->
                        // Login and API docs need to be reachable with no token at all.
                        auth.requestMatchers("/api/auth/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                            // Registration has to be public too - there's no token to prove who
                            // you are before you have an account. Only POST: listing/managing
                            // existing users stays behind authentication.
                            .requestMatchers(HttpMethod.POST, "/api/users").permitAll()
                                .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness", "/actuator/health").permitAll()
                            .anyRequest().authenticated())
                .authenticationProvider(authenticationProvider())
                // Runs before Spring's own form-login filter, so a valid Bearer token
                // authenticates the request before anything else gets a chance to reject it.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();

     }
}
