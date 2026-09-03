package com.sina.banking.events;

import com.sina.banking.models.TransactionStatus;
import com.sina.banking.models.TransactionType;

public record TransactionPostedEvent(
        Integer transactionId,
        TransactionType type,
        TransactionStatus status
) {
}
