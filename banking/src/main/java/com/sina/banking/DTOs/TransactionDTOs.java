package com.sina.banking.DTOs;

import com.sina.banking.models.Transaction;
import com.sina.banking.models.TransactionStatus;
import com.sina.banking.models.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

public class TransactionDTOs {
    public record CreateTransactionRequest(
            @Schema(description = "Unique client-generated key - retrying the same key returns the original result instead of creating a duplicate transaction")
            String idempotencyKey,
            @Schema(description = "Amount in minor units (e.g. cents), must be positive")
            Long amount,
            @Schema(description = "ISO 4217 currency code, must match the account's currency", example = "USD")
            String currency,
            @Schema(description = "Id of the account to deposit into or withdraw from")
            Integer accountId
    ) {}

    public record TransferRequest (
            @Schema(description = "Unique client-generated key - retrying the same key returns the original result instead of creating a duplicate transaction")
            String idempotencyKey,
            @Schema(description = "Amount in minor units (e.g. cents), must be positive")
            Long amount,
            @Schema(description = "ISO 4217 currency code, must match both accounts' currency", example = "USD")
            String currency,
            @Schema(description = "Id of the account funds are withdrawn from")
            Integer fromAccountId,
            @Schema(description = "Id of the account funds are deposited into")
            Integer toAccountId
    ) {}

    public record TransactionResponse(
            Integer id,
            TransactionType transactionType,
            TransactionStatus transactionStatus,
            String idempotencyKey,
            LocalDateTime createdAt,
            LocalDateTime updateAt
    ) {
        public static TransactionResponse from(Transaction transaction) {
            return new TransactionResponse(
                    transaction.getId(),
                    transaction.getType(),
                    transaction.getStatus(),
                    transaction.getIdempotencyKey(),
                    transaction.getCreatedAt(),
                    transaction.getUpdatedAt()
            );
        }
    }

    public record ReverseTransactionRequest(
            @Schema(description = "Unique client-generated key - retrying the same key returns the original result instead of creating a duplicate reversal")
            String idempotencyKey,
            @Schema(description = "Id of the POSTED transaction to reverse")
            Integer transactionId
    ) {}
}
