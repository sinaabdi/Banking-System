package com.sina.banking.DTOs;

import com.sina.banking.models.Account;
import com.sina.banking.models.AccountStatus;
import com.sina.banking.models.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

public class AccountDTOs {
    public record CreateAccountRequest(
            @Schema(description = "Id of the user who will own this account")
            Integer userId,
            @Schema(description = "CHECKING or SAVINGS - SYSTEM accounts are created internally and cannot be requested here")
            AccountType type,
            @Schema(description = "ISO 4217 currency code", example = "USD")
            String currency
    ) {}

    public record AccountResponse(
            Integer id,
            Integer userId,
            Long accountNumber,
            AccountType type,
            String currency,
            AccountStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static AccountResponse from(Account account) {
            return new AccountResponse(
                    account.getId(),
                    account.getUser().getId(),
                    account.getAccountNumber(),
                    account.getType(),
                    account.getCurrency(),
                    account.getStatus(),
                    account.getCreatedAt(),
                    account.getUpdatedAt()
            );
        }
    }
}
