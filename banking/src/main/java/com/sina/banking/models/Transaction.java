package com.sina.banking.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@EntityListeners(AuditingEntityListener.class)
@Setter @Getter
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "reversed_transaction_id")
    private Integer reversedTransactionId;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public Transaction() {}

    public Transaction(TransactionType type, TransactionStatus status, String idempotencyKey) {
        this.type = type;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
    }

    public void pendingTransaction() {
        this.status = TransactionStatus.PENDING;
    }

    public void postedTransaction() {
        this.status = TransactionStatus.POSTED;
    }

    public void failedTransaction() {
        this.status = TransactionStatus.FAILED;
    }

    public void revesedTransaction() {
        this.status = TransactionStatus.REVERSED;
    }

    public void transferTransaction() {
        this.type = TransactionType.TRANSFER;
    }

    public void depositTransaction() {
        this.type = TransactionType.DEPOSIT;
    }

    public void withdrawlTransaction() {
        this.type = TransactionType.WITHDRAWAL;
    }

    public void feeTransaction() {
        this.type = TransactionType.FEE;
    }

    public void reversalTransaction() {
        this.type = TransactionType.REVERSAL;
    }
}
