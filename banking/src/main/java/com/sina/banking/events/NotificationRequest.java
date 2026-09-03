package com.sina.banking.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sina.banking.models.TransactionStatus;
import com.sina.banking.models.TransactionType;

public record NotificationRequest(
        @JsonProperty("transaction_id")
        Integer transactionId,
        TransactionType type,
        TransactionStatus status
) {
    
}
