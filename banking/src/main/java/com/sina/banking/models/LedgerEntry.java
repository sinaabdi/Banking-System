package com.sina.banking.models;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

// Append-only: no setters and no updatedAt, deliberately. A ledger entry is never edited once
// written - correcting a mistake means posting a new, opposite entry (a reversal), never mutating
// this one, so the history always reflects exactly what happened and when.
@Entity
@Table(name = "ledger_entries")
@EntityListeners(AuditingEntityListener.class)
@Getter
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private TransactionDirection direction;

    // Minor units (e.g. cents), always positive - direction carries the sign. Kept as an integer
    // rather than a decimal to avoid floating-point rounding entirely.
    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @CreatedDate
    private LocalDateTime createdAt;

    public LedgerEntry() {}

    public LedgerEntry(Transaction transaction, Account account, TransactionDirection direction, Long amount, String currency) {
        this.transaction = transaction;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
    }
}
