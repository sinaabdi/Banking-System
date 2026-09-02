package com.sina.banking.repositories;

import com.sina.banking.models.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    // Balance is never stored - it's always derived by summing this account's ledger entries,
    // CREDIT adding and DEBIT subtracting, so it can never drift out of sync with the transaction
    // history that produced it.
    @Query("""
    select coalesce(sum (
        case when le.direction = com.sina.banking.models.TransactionDirection.CREDIT
            then le.amount
            else -le.amount
        end
        ), 0)
        from LedgerEntry le
        where le.account.id = :accountId
    """)
    long computeBalanceForAccount(@Param("accountId") Integer accountId);

    List<LedgerEntry> findByTransactionId(Integer transactionId);
}
