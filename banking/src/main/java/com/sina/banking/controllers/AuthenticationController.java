package com.sina.banking.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.sina.banking.DTOs.AuthDTOs.LoginRequest;
import com.sina.banking.DTOs.AuthDTOs.LoginResponse;
import com.sina.banking.services.AuthenticationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;


@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationController.class);

    private final AuthenticationService authService;

    public AuthenticationController(AuthenticationService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Login user with username and password and get JWT token")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login succeeded, JWT returned"),
            @ApiResponse(responseCode = "401", description = "Username or password is incorrect, or the account is disabled")
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        log.debug("POST /api/auth/login username={}", request.username());
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }
}
