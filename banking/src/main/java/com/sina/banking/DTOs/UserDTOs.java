package com.sina.banking.DTOs;

import com.sina.banking.models.User;
import com.sina.banking.models.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

public class UserDTOs {
    public record CreateUserRequest(
        String firstName,
        String lastName,
        String username,
        @Schema(description = "Plain-text password - hashed before storage, never stored or logged as-is")
        String password,
        String email
    ){}

    public record UpdateUserRequest(
            String firstName,
            String lastName,
            String email
    ) {}

    public record ChangePasswordRequest(
            @Schema(description = "The user's current plain-text password, verified before the change is applied")
            String currentPassword,
            @Schema(description = "The new plain-text password to set")
            String newPassword
    ) {}

    public record UserResponse(
            Integer id,
            String firstName,
            String lastName,
            String username,
            String email,
            UserStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static UserResponse from(User user) {
            return new UserResponse(
                    user.getId(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getUsername(),
                    user.getEmail(),
                    user.getStatus(),
                    user.getCreatedAt(),
                    user.getUpdatedAt()
            );
        }
    }
}
