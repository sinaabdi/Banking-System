package com.sina.banking.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sina.banking.models.TransactionStatus;
import com.sina.banking.models.TransactionType;
import jakarta.annotation.Nullable;

// The wire shape sent to notification-service - kept separate from TransactionPostedEvent because
// the Go side's struct tags are snake_case; @JsonProperty bridges just the one mismatched field.
// type/status need no annotation - Jackson serializes an enum as its name() by default.
public record TransactionEventPayload(
        @JsonProperty("transaction_id")
        Integer transactionId,
        TransactionType type,
        TransactionStatus status,
        Long amount,
        String currency,
        @JsonProperty("account_id")
        Integer accountId,
        @JsonProperty("user_id")
        Integer userId,
        @Nullable
        @JsonProperty("counterparty_account_id")
        Integer counterpartyAccountId,
        @Nullable
        @JsonProperty("counterparty_user_id")
        Integer counterpartyUserId
) {
}
