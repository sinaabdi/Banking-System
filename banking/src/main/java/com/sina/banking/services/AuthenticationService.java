package com.sina.banking.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.sina.banking.DTOs.AuthDTOs.LoginRequest;
import com.sina.banking.DTOs.AuthDTOs.LoginResponse;
import com.sina.banking.security.AppUserDetailsService;
import com.sina.banking.security.JwtService;

@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final AuthenticationManager authenticationManager;
    private final AppUserDetailsService appUserDetailsService;
    private final JwtService jwtService;

    public AuthenticationService(AuthenticationManager authenticationManager, AppUserDetailsService appUserDetailsService, JwtService jwtService){
        this.authenticationManager = authenticationManager;
        this.appUserDetailsService = appUserDetailsService;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        log.debug("Processing login for username={}", request.username());
        // Never log request.password() here or anywhere downstream - only non-secret fields.
        // authenticate() does the real work: loads the user via AppUserDetailsService, then
        // checks the password against the stored hash - throws AuthenticationException (mapped
        // to 401 by GlobalExceptionHandler) on a wrong password, unknown username, or a
        // DISABLED user (AppUserPrincipal.isEnabled()), without us checking anything ourselves.
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(request.username(), request.password());
        authenticationManager.authenticate(authToken);

        // Reloaded rather than taken from authToken's result - simpler to reason about, at the
        // cost of one extra lookup we could avoid by casting the Authentication's principal.
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(request.username());
        String token = jwtService.generateJwtToken(userDetails);
        log.info("Login succeeded for username={}", request.username());
        return new LoginResponse(token);
    }

}
