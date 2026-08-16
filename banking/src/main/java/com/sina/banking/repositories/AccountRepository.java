package com.sina.banking.repositories;

import com.sina.banking.models.Account;
import com.sina.banking.models.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Integer> {
    Optional<Account> findByAccountNumber(Long accountNumber);

    List<Account> findByUserId(Integer userId);
}
