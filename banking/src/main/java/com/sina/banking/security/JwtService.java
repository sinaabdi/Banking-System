package com.sina.banking.security;

import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

// Issues and validates our own signed JWTs (HMAC) - stateless auth, no server-side session/token
// store. A token can't be revoked early; jwt.expiration-ms is the only lever against a leaked one,
// so keep it short. Never log a token or the signing secret itself, only usernames.
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms}")
    private long expirationMs;

    public String generateJwtToken(UserDetails userDetails) {
        Instant expiry = Instant.now().plusMillis(expirationMs);

        String token = Jwts.builder()
            .subject(userDetails.getUsername())
            .issuedAt(new Date())
            .expiration(Date.from(expiry))
            .signWith(getSigningKey())
            .compact();
        log.debug("Generated JWT for username={}, expiresAt={}", userDetails.getUsername(), expiry);
        return token;
    }

    public String extractUsername(String token) {
        Claims claims = extractAllClaims(token);
        return claims.getSubject();
    }

    public Date extractExpiration(String token) {
        Claims claims = extractAllClaims(token);
        return claims.getExpiration();
    }

    public Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }
    public Boolean isTokenValid(String token, UserDetails userDetails) {
        return userDetails.getUsername().equals(extractUsername(token)) && !isTokenExpired(token);
    }

    private final SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    // Verifies the signature as part of parsing - a tampered or wrongly-signed token throws here
    // (SignatureException) rather than silently returning claims. An already-expired token also
    // throws here (ExpiredJwtException), before isTokenExpired's own date check ever runs -
    // callers (JwtAuthenticationFilter) need to catch JwtException around calls into this class.
    private final Claims extractAllClaims(String token) {
        return Jwts.parser()
        .verifyWith(getSigningKey())
        .build()
        .parseSignedClaims(token)
        .getPayload();
    }
}
