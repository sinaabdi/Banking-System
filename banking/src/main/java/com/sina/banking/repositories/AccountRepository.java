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

    @Query(value = "SELECT NEXTVAL('account_number_seq')", nativeQuery = true)
    Long nextAccountNumberSeed();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Integer id);
}
