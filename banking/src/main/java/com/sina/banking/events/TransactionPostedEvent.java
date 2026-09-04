package com.sina.banking.events;

import com.sina.banking.models.TransactionStatus;
import com.sina.banking.models.TransactionType;

// Internal Spring application event, published in-process - not the same thing as the message that
// goes out over RabbitMQ (see NotificationRequest for that wire shape).
public record TransactionPostedEvent(
        Integer transactionId,
        TransactionType type,
        TransactionStatus status
) {
}
