package com.sina.banking.services;

import com.sina.banking.DTOs.AccountDTOs.AccountResponse;
import com.sina.banking.DTOs.AccountDTOs.CreateAccountRequest;
import com.sina.banking.models.Account;
import com.sina.banking.models.User;
import com.sina.banking.repositories.AccountRepository;
import com.sina.banking.repositories.LedgerEntryRepository;
import com.sina.banking.repositories.UserRepository;
import com.sina.banking.utils.AccountNumberGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

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
        log.debug("Looking up owning user id={} for new account", request.userId());
        User user = userRepository.findById(request.userId()).orElseThrow(() -> new NoSuchElementException("user not found: " + request.userId()));

        // Account number = a DB sequence value with a Luhn check digit appended, so a single
        // mistyped digit in a manually-entered account number gets caught before any money moves.
        Long accountNumber = generateAccountNumber();
        log.debug("Generated account number {} for user id={}", accountNumber, user.getId());

        Account account = new Account(user, accountNumber, request.type(), request.currency());

        Account saved = accountRepository.save(account);
        log.info("Created account id={} userId={} type={} currency={}", saved.getId(), user.getId(), saved.getType(), saved.getCurrency());
        return AccountResponse.from(saved);
    }

    @Transactional
    public void freezeAccount(Integer id) {
        Account account = findAccountOrThrow(id);
        account.freeze();
        log.info("Froze account id={}", id);
    }

    @Transactional
    public void closeAccount(Integer id) {
        Account account = findAccountOrThrow(id);
        account.close();
        log.info("Closed account id={}", id);
    }

    @Transactional
    public void activeAccount(Integer id) {
        Account account = findAccountOrThrow(id);
        account.active();
        log.info("Activated account id={}", id);
    }

    public long getBalance(Integer accountId) {
        findAccountOrThrow(accountId); // Check if account exists
        // Balance is never stored directly - it's always the sum of this account's ledger
        // entries, so it can never drift out of sync with the transaction history that produced it.
        long balance = ledgerEntryRepository.computeBalanceForAccount(accountId);
        log.debug("Computed balance for account id={}: {}", accountId, balance);
        return balance;
    }

    public AccountResponse getAccountByAccountId(Integer id) {
        log.debug("Fetching account id={}", id);
        return AccountResponse.from(findAccountOrThrow(id));
    }

    public List<AccountResponse> getAccountsForUser(Integer userId) {
        log.debug("Fetching accounts for user id={}", userId);
        return accountRepository.findByUserId(userId).stream().
                map(AccountResponse::from).toList();
    }

    public AccountResponse getAccountByAccountNumber(Long accountNumber) {
        log.debug("Fetching account by account number");
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
