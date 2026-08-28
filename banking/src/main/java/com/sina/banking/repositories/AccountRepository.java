package com.sina.banking.repositories;

import com.sina.banking.models.Account;
import com.sina.banking.models.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Integer> {
    Optional<Account> findByAccountNumber(Long accountNumber);

    Optional<Account> findAccountByTypeAndCurrency(AccountType type, String currency);

    List<Account> findByUserId(Integer userId);

    // A bare sequence has no backing entity, so this has to be a native query rather than
    // a derived/JPQL one.
    @Query(value = "SELECT NEXTVAL('account_number_seq')", nativeQuery = true)
    Long nextAccountNumberSeed();

    // Acquires a row lock (SELECT ... FOR UPDATE) held for the rest of the caller's transaction.
    // Use this instead of findById whenever a balance-based decision (withdraw/transfer) is about
    // to be made for the account - otherwise two concurrent operations could both read the same
    // stale balance and both proceed.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Integer id);
}
