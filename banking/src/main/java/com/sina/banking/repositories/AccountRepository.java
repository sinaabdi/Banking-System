package com.sina.banking.repositories;

import com.sina.banking.models.Account;
import com.sina.banking.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Integer> {
    Optional<Account> findByAccountNumber(Long accountNumber);
    List<Account> findByUserId(Integer userId);

    @Query(value = "SELECT NEXTVAL('account_number_seq')", nativeQuery = true)
    Long nextAccountNumberSeed();
}
