package com.sina.banking.repositories;

import com.sina.banking.models.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
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
}
