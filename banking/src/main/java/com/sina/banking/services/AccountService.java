package com.sina.banking.services;

import com.sina.banking.DTOs.AccountDtos.AccountResponse;
import com.sina.banking.DTOs.AccountDtos.CreateAccountRequest;
import com.sina.banking.models.Account;
import com.sina.banking.models.User;
import com.sina.banking.repositories.AccountRepository;
import com.sina.banking.repositories.LedgerEntryRepository;
import com.sina.banking.repositories.UserRepository;
import com.sina.banking.utils.AccountNumberGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        User user = userRepository.findById(request.userId()).orElseThrow(() -> new NoSuchElementException("user not found: " + request.userId()));

        Long accountNumber = generateAccountNumber();

        Account account = new Account(user, accountNumber, request.type(), request.currency());

        Account saved = accountRepository.save(account);
        return AccountResponse.from(saved);
    }

    @Transactional
    public void freezeAccount(Integer id) {
        Account account = findAccountOrThrow(id);
        account.freeze();
    }

    @Transactional
    public void closeAccount(Integer id) {
        Account account = findAccountOrThrow(id);
        account.close();
    }

    @Transactional
    public void activeAccount(Integer id) {
        Account account = findAccountOrThrow(id);
        account.active();
    }

    public long getBalance(Integer accountId) {
        findAccountOrThrow(accountId); // Check if account exists
        return ledgerEntryRepository.computeBalanceForAccount(accountId);
    }

    public AccountResponse getAccountByAccountId(Integer id) {
        return AccountResponse.from(findAccountOrThrow(id));
    }

    public List<AccountResponse> getAccountsForUser(Integer userId) {
        return accountRepository.findByUserId(userId).stream().
                map(AccountResponse::from).toList();
    }

    public AccountResponse getAccountByAccountNumber(Long accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new NoSuchElementException("account not found: " + accountNumber));
        return AccountResponse.from(account);
    }

    private Account findAccountOrThrow(Integer id) {
        return accountRepository.findById(id).orElseThrow(() -> new NoSuchElementException("account not found: " + id));
    }

    private Long generateAccountNumber() {
        Long seed = accountRepository.nextAccountNumberSeed();
        return AccountNumberGenerator.withCheckDigit(seed);
    }
}
