package com.sina.banking.DTOs;

import com.sina.banking.models.Account;
import com.sina.banking.models.AccountStatus;
import com.sina.banking.models.AccountType;

import java.time.LocalDateTime;

public class AccountDtos {
    public record CreateAccountRequest(
            Integer userId,
            AccountType type,
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
