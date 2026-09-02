package com.sina.banking.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@EntityListeners(AuditingEntityListener.class)
@Setter @Getter
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "account_number", unique = true, nullable = false)
    private Long accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType type;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    // Optimistic lock for direct mutations to this row (freeze/close/activate). Not what protects
    // withdraw/transfer's balance check, though - those never write to this row directly, so they
    // rely on a pessimistic row lock instead (see AccountRepository.findByIdForUpdate).
    @Column(name = "version")
    @Version
    private Integer version;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public Account() {}

    public Account(User user, Long accountNumber, AccountType type, String currency) {
        this.user = user;
        this.accountNumber = accountNumber;
        this.type = type;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
    }

    public void freeze() {
        this.status = AccountStatus.FROZEN;
    }

    public void close() {
        this.status = AccountStatus.CLOSED;
    }

    public void active() {
        this.status = AccountStatus.ACTIVE;
    }
}
