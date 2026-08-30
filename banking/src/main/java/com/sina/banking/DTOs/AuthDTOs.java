package com.sina.banking.DTOs;

import io.swagger.v3.oas.annotations.media.Schema;

public class AuthDTOs {
    public record LoginRequest(
        String username,
        @Schema(description = "Plain-text password, verified against the stored BCrypt hash - never logged")
        String password
    ) {}

    public record LoginResponse(
        @Schema(description = "Signed JWT - send as 'Authorization: Bearer <token>' on subsequent requests")
        String token
    ) {}
}
