package com.sina.banking.DTOs;

import com.sina.banking.models.User;
import com.sina.banking.models.UserStatus;

import java.time.LocalDateTime;

public class UserDtos {
    public record CreateUserRequest(
        String firstName,
        String lastName,
        String username,
        String password,
        String email
    ){}

    public record UpdateUserRequest(
            String firstName,
            String lastName,
            String email
    ) {}

    public record ChangePasswordRequest(
            String currentPassword,
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
