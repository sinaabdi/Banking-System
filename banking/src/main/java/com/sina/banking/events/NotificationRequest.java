package com.sina.banking.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sina.banking.models.TransactionStatus;
import com.sina.banking.models.TransactionType;

// The wire shape sent to notification-service - kept separate from TransactionPostedEvent because
// the Go side's struct tags are snake_case; @JsonProperty bridges just the one mismatched field.
// type/status need no annotation - Jackson serializes an enum as its name() by default.
public record NotificationRequest(
        @JsonProperty("transaction_id")
        Integer transactionId,
        TransactionType type,
        TransactionStatus status
) {
}
