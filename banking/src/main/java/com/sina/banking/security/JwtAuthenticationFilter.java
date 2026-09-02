package com.sina.banking.security;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Runs once per request, before the rest of the filter chain, and populates SecurityContext
// from a Bearer JWT if one is present and valid. A missing or invalid token isn't rejected here -
// it just leaves the request unauthenticated, and SecurityConfig's authorizeHttpRequests rules
// decide whether that's actually allowed for the endpoint being called.
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final AppUserDetailsService appUserDetailsService;
    private final JwtService jwtService;

    public JwtAuthenticationFilter(AppUserDetailsService userDetailsService, JwtService jwtService) {
        this.appUserDetailsService = userDetailsService;
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String authHeader = request.getHeader("Authorization");
        String token = null;
        String username = null;
        
        try {
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7);
                username = jwtService.extractUsername(token);
            }

            // Skip if something upstream already authenticated this request, so we don't redo
            // the UserDetails lookup/validation for no reason.
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
                if (jwtService.isTokenValid(token, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userDetails,
                        null,
                        userDetails.getAuthorities());

                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.debug("Authenticated request as username={}", username);
                }
            }
        } catch (JwtException exception) {
            // Expired/tampered/malformed token - don't 500 the request, just leave it
            // unauthenticated and let authorizeHttpRequests decide if that's allowed.
            log.debug("JWT threw an exception for user {}: {}", username, exception.toString());
        }
        finally {
            filterChain.doFilter(request, response);
        }

    }
    
}
