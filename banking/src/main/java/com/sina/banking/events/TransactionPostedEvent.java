package com.sina.banking.events;

import com.sina.banking.models.TransactionStatus;
import com.sina.banking.models.TransactionType;
import jakarta.annotation.Nullable;

// Internal Spring application event, published in-process - not the same thing as the message that
// goes out over RabbitMQ (see TransactionEventPayload for that wire shape).
public record TransactionPostedEvent(
        Integer transactionId,
        TransactionType type,
        TransactionStatus status,
        Long amount,
        String currency,
        Integer accountId,
        Integer userId,
        @Nullable
        Integer counterpartyAccountId,
        @Nullable
        Integer counterpartyUserId
) {
}
