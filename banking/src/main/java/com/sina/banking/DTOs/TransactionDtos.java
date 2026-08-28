package com.sina.banking.DTOs;

import com.sina.banking.models.Transaction;
import com.sina.banking.models.TransactionStatus;
import com.sina.banking.models.TransactionType;

import java.time.LocalDateTime;

public class TransactionDtos {
    public record CreateTransactionRequest(
            String idempotencyKey,
            Long amount,
            String currency,
            Integer accountId
    ) {}

    public record  TransferRequest (
            String idempotencyKey,
            Long amount,
            String currency,
            Integer fromAccountId,
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
}
