package com.sina.banking.services;

import com.sina.banking.DTOs.TransactionDtos.TransferRequest;
import com.sina.banking.DTOs.TransactionDtos.TransactionResponse;
import com.sina.banking.DTOs.TransactionDtos.CreateTransactionRequest;
import com.sina.banking.models.*;
import com.sina.banking.repositories.AccountRepository;
import com.sina.banking.repositories.LedgerEntryRepository;
import com.sina.banking.repositories.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private  final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;

    public TransactionService(TransactionRepository transactionRepository,
                              LedgerEntryRepository ledgerEntryRepository,
                              AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public TransactionResponse deposit(CreateTransactionRequest request) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Deposit replay for idempotencyKey={} -> returning existing transaction id={}",
                    request.idempotencyKey(), existing.get().getId());
            return TransactionResponse.from(existing.get());
        }

        if (request.amount() <= 0) {
            throw new IllegalArgumentException("deposit amount must be positive.");
        }

        // fetch account
        Account destinationAccount = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new NoSuchElementException("account does not exists: " + request.accountId()));

        // check account status
        checkAccountActiveOrElseThrow(destinationAccount);
        // check if currency is correct
        checkCurrencyMatchOrElseThrow(destinationAccount, request);

        Account cashAccount = accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, request.currency())
                .orElseThrow(() -> new NoSuchElementException("no system account is defined for this currency: " + request.currency()));

        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.PENDING, request.idempotencyKey());

        transaction = transactionRepository.save(transaction);

        LedgerEntry creditEntry = new LedgerEntry(transaction, destinationAccount, TransactionDirection.CREDIT, request.amount(), request.currency());
        LedgerEntry debitEntry = new LedgerEntry(transaction, cashAccount, TransactionDirection.DEBIT, request.amount(), request.currency());

        ledgerEntryRepository.save(creditEntry);
        ledgerEntryRepository.save(debitEntry);

        transaction.postedTransaction();

        log.info("Posted deposit transaction id={} accountId={} cashAccountId={} amount={} currency={}",
                transaction.getId(), destinationAccount.getId(), cashAccount.getId(), request.amount(), request.currency());

        return TransactionResponse.from(transaction);
    }

    @Transactional
    public TransactionResponse withdraw(CreateTransactionRequest request) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Withdraw replay for idempotencyKey={} -> returning existing transaction id={}",
                    request.idempotencyKey(), existing.get().getId());
            return TransactionResponse.from(existing.get());
        }

        if (request.amount() <= 0) {
            throw new IllegalArgumentException("withdraw amount must be positive");
        }

        Account destinationAccount = findAccountForUpdateOrElseThrow(request.accountId());

        checkAccountActiveOrElseThrow(destinationAccount);
        checkCurrencyMatchOrElseThrow(destinationAccount, request);

        Long balance = ledgerEntryRepository.computeBalanceForAccount(destinationAccount.getId());
        if (request.amount() > balance) {
            log.warn("not enough balance for withdraw for account id {} - balance: {}", destinationAccount.getId(), balance);
            throw new IllegalArgumentException("insufficient funds");
        }

        Account cashAccount = accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, request.currency())
                .orElseThrow(() -> new NoSuchElementException("no system account is defined for this currency: " + request.currency()));

        Transaction transaction = new Transaction(TransactionType.WITHDRAWAL, TransactionStatus.PENDING, request.idempotencyKey());
        transaction = transactionRepository.save(transaction);

        LedgerEntry debitEntry = new LedgerEntry(transaction, destinationAccount, TransactionDirection.DEBIT, request.amount(), request.currency());
        LedgerEntry creditEntry = new LedgerEntry(transaction, cashAccount, TransactionDirection.CREDIT, request.amount(), request.currency());

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        transaction.postedTransaction();

        log.info("Posted withdraw transaction id={} accountId={} cashAccountId={} amount={} currency={}",
                transaction.getId(), destinationAccount.getId(), cashAccount.getId(), request.amount(), request.currency());

        return TransactionResponse.from(transaction);
    }

    @Transactional
    public TransactionResponse transfer(TransferRequest request) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Transfer replay for idempotencyKey={} -> returning existing transaction id={}",
                    request.idempotencyKey(), existing.get().getId());
            return TransactionResponse.from(existing.get());
        }

        if (request.amount() <= 0) {
            throw new IllegalArgumentException("transfer amount must be positive");
        }

        // Reject transfer an account to itself
        if (request.toAccountId().equals(request.fromAccountId())) {
            throw new IllegalArgumentException("cannot transfer to the same account.");
        }

        Account toAccount;
        Account fromAccount;

        if (request.toAccountId() <= request.fromAccountId()) {
            toAccount = findAccountForUpdateOrElseThrow(request.toAccountId());
            fromAccount = findAccountForUpdateOrElseThrow(request.fromAccountId());
        } else {
            fromAccount = findAccountForUpdateOrElseThrow(request.fromAccountId());
            toAccount = findAccountForUpdateOrElseThrow(request.toAccountId());
        }

        checkAccountActiveOrElseThrow(toAccount);
        checkAccountActiveOrElseThrow(fromAccount);

        // Currency check on toAccount and fromAccount against request
        checkCurrencyMatchForTransferOrElseThrow(toAccount, fromAccount, request);

        long balance = ledgerEntryRepository.computeBalanceForAccount(fromAccount.getId());
        if (request.amount() > balance) {
            throw new IllegalArgumentException("insufficient funds");
        }

        Transaction transaction = new Transaction(TransactionType.TRANSFER, TransactionStatus.PENDING, request.idempotencyKey());
        transaction = transactionRepository.save(transaction);

        LedgerEntry debitEntry = new LedgerEntry(transaction, fromAccount, TransactionDirection.DEBIT, request.amount(), request.currency());
        LedgerEntry creditEntry = new LedgerEntry(transaction, toAccount, TransactionDirection.CREDIT, request.amount(), request.currency());

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        transaction.postedTransaction();

        log.info("Posted transfer transaction id={} from accountId={} to AccountId={} amount={} currency={}",
                transaction.getId(), fromAccount.getId(), toAccount.getId(), request.amount(), request.currency());

        return TransactionResponse.from(transaction);
    }

    public TransactionResponse getTransactionById(Integer id) {
        return TransactionResponse.from(findTransactionOrThrow(id));
    }

    public TransactionResponse getTransactionByIdempotencyKey(String idempotencyKey) {
        Transaction transaction = transactionRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new NoSuchElementException("idempotency key not found: " + idempotencyKey));
        return TransactionResponse.from(transaction);
    }

    private Transaction findTransactionOrThrow(Integer id) {
        return transactionRepository.findById(id).orElseThrow(() -> new NoSuchElementException("transaction not found: " + id));
    }

    private void checkAccountActiveOrElseThrow(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            log.warn("Account {} is not in active state.", account.getId());
            throw new IllegalArgumentException("the account is not active");
        }
    }

    private void checkCurrencyMatchOrElseThrow(Account account, CreateTransactionRequest request) {
        if (!request.currency().equals(account.getCurrency())) {
            log.warn("Currency mismatch, request currency: {}, destination account currency: {}", request.currency(), account.getCurrency());
            throw new IllegalArgumentException("currency mismatch: account is " + account.getCurrency());
        }
    }

    private void checkCurrencyMatchForTransferOrElseThrow(Account to, Account from, TransferRequest request) {
        if (!request.currency().equals(to.getCurrency()) || !request.currency().equals(from.getCurrency())) {
            throw new IllegalArgumentException("currency mismatch, source account currency: " + from.getCurrency() + " , destination account currency: " + to.getCurrency()
            + " , request currency: " + request.currency());
        }
    }

    private Account findAccountForUpdateOrElseThrow(Integer id) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("account does not exists: " + id));
    }
}
