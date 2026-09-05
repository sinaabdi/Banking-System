package com.sina.banking.services;

import com.sina.banking.DTOs.TransactionDTOs.ReverseTransactionRequest;
import com.sina.banking.DTOs.TransactionDTOs.TransferRequest;
import com.sina.banking.events.TransactionPostedEvent;
import com.sina.banking.DTOs.TransactionDTOs.TransactionResponse;
import com.sina.banking.DTOs.TransactionDTOs.CreateTransactionRequest;
import com.sina.banking.models.*;
import com.sina.banking.repositories.AccountRepository;
import com.sina.banking.repositories.LedgerEntryRepository;
import com.sina.banking.repositories.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransactionServiceTest {

    private final String IDEMPOTENCY_KEY = "idem-key-123";
    private final Integer CALLER_ID = 1;
    private final Integer WRONG_CALLER_ID = 99;

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private ApplicationEventPublisher publisher;

    @InjectMocks
    private TransactionService transactionService;

    private Account account;
    private User owner;
    private User transferUser;
    private Account systemAccount;
    private Account transferToAccount;
    private CreateTransactionRequest request;
    private TransferRequest transferRequest;
    private ReverseTransactionRequest reverseRequest;

    @BeforeEach
    void setup(){
        account = mock(Account.class);
        owner = mock(User.class);
        lenient().when(owner.getId()).thenReturn(CALLER_ID);
        lenient().when(account.getUser()).thenReturn(owner);
        systemAccount = mock(Account.class);
        transferToAccount = mock(Account.class);
        transferUser = mock(User.class);
        lenient().when(transferUser.getId()).thenReturn(2);
        lenient().when(transferToAccount.getUser()).thenReturn(transferUser);
        lenient().when(systemAccount.getType()).thenReturn(AccountType.SYSTEM);
        request = new CreateTransactionRequest(
                IDEMPOTENCY_KEY,
                100L,
                "USD",
                1);
        transferRequest = new TransferRequest(
                IDEMPOTENCY_KEY,
                100L,
                "USD",
                1,
                10
        );
        reverseRequest = new ReverseTransactionRequest(
                IDEMPOTENCY_KEY,
                1
        );
    }

    @Test
    void deposit_happyPath_postsTransactionAndCreatesTwoLedgerEntries(){
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(request.currency());
        when(accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, request.currency())).thenReturn(Optional.of(systemAccount));

        Transaction savedTransaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.PENDING, request.idempotencyKey());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        TransactionResponse response = transactionService.deposit(request, CALLER_ID, false);

        assertThat(response).isNotNull();

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();

        LedgerEntry creditEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.CREDIT).findFirst().orElseThrow();
        LedgerEntry debitEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.DEBIT).findFirst().orElseThrow();

        
        ArgumentCaptor<TransactionPostedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionPostedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());

        assertThat(eventCaptor.getValue().type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(eventCaptor.getValue().status()).isEqualTo(TransactionStatus.POSTED);

        assertThat(creditEntry.getAccount()).isEqualTo(account);
        assertThat(creditEntry.getAmount()).isEqualTo(100L);
        assertThat(debitEntry.getAccount()).isEqualTo(systemAccount);
        assertThat(debitEntry.getAmount()).isEqualTo(100L);
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(response.transactionType()).isEqualTo(TransactionType.DEPOSIT);
    }

    @Test
    void deposit_idempotencyKeyAlreadyExists() {
        Transaction existingTransaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.PENDING, request.idempotencyKey());
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.of(existingTransaction));

        TransactionResponse response = transactionService.deposit(request, CALLER_ID, false);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(existingTransaction.getIdempotencyKey());
        assertThat(response.transactionStatus()).isEqualTo(existingTransaction.getStatus());

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(ledgerEntryRepository);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void deposit_amountIsNotPositive() {
        CreateTransactionRequest zeroAmountRequest = new CreateTransactionRequest(IDEMPOTENCY_KEY, 0L, "USD", 1);
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.deposit(zeroAmountRequest, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        verifyNoInteractions(accountRepository);
    }

    @Test
    void deposit_destinationAccountDoesNotExists(){
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(request.accountId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.deposit(request, CALLER_ID, false)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account does not exist");
    }

    @Test
    void deposit_callerDoesNotOwnAccount() {
        User someoneElse = mock(User.class);
        when(someoneElse.getId()).thenReturn(999);
        when(account.getUser()).thenReturn(someoneElse);
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(request.accountId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> transactionService.deposit(request, CALLER_ID, false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("does not belong to caller");

    }

    @Test
    void deposit_callerDepositWithAdminRole() {
        User someoneElse = mock(User.class);
        LedgerEntry ledgerEntry = mock(LedgerEntry.class);
        lenient().when(someoneElse.getId()).thenReturn(999);
        lenient().when(account.getUser()).thenReturn(someoneElse);
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(request.currency());
        when(accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, request.currency())).thenReturn(Optional.of(systemAccount));

        Transaction savedTransaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.PENDING, request.idempotencyKey());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        TransactionResponse response = transactionService.deposit(request, CALLER_ID, true);

        assertThat(response).isNotNull();

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();

        LedgerEntry creditEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.CREDIT).findFirst().orElseThrow();
        LedgerEntry debitEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.DEBIT).findFirst().orElseThrow();

        assertThat(creditEntry.getAccount()).isEqualTo(account);
        assertThat(creditEntry.getAmount()).isEqualTo(100L);
        assertThat(debitEntry.getAccount()).isEqualTo(systemAccount);
        assertThat(debitEntry.getAmount()).isEqualTo(100L);
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(response.transactionType()).isEqualTo(TransactionType.DEPOSIT);

    }

    @Test
    void deposit_destinationAccountIsNotActive() {
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.FROZEN);

        assertThatThrownBy(() -> transactionService.deposit(request, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the account is not active");
    }

    @Test
    void deposit_destinationAccountCurrencyMismatch() {
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn("CAD");

        assertThatThrownBy(() -> transactionService.deposit(request, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void deposit_noSystemAccountExistsForCurrency() {
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(request.currency());
        when(accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, request.currency())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.deposit(request, CALLER_ID, false)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no system account is defined for this currency");
    }

    @Test
    void withdraw_happyPath_postTransactionAndCreateTwoLedgerEntries() {
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(request.currency());
        when(ledgerEntryRepository.computeBalanceForAccount(account.getId())).thenReturn(200L);
        when(accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, request.currency())).thenReturn(Optional.of(systemAccount));

        Transaction savedTransaction = new Transaction(TransactionType.WITHDRAWAL, TransactionStatus.PENDING, request.idempotencyKey());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        TransactionResponse response = transactionService.withdraw(request, CALLER_ID, false);

        assertThat(response).isNotNull();

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();

        LedgerEntry creditEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.CREDIT).findFirst().orElseThrow();
        LedgerEntry debitEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.DEBIT).findFirst().orElseThrow();

        ArgumentCaptor<TransactionPostedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionPostedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());

        assertThat(eventCaptor.getValue().type()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(eventCaptor.getValue().status()).isEqualTo(TransactionStatus.POSTED);

        assertThat(debitEntry.getAccount()).isEqualTo(account);
        assertThat(debitEntry.getAmount()).isEqualTo(100L);
        assertThat(creditEntry.getAccount()).isEqualTo(systemAccount);
        assertThat(creditEntry.getAmount()).isEqualTo(100L);
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(response.transactionType()).isEqualTo(TransactionType.WITHDRAWAL);
    }

    @Test
    void withdraw_idempotencyKeyAlreadyExists() {
        Transaction existingTransaction = new Transaction(TransactionType.WITHDRAWAL, TransactionStatus.PENDING, request.idempotencyKey());
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.of(existingTransaction));

        TransactionResponse response = transactionService.withdraw(request, CALLER_ID, false);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(existingTransaction.getIdempotencyKey());
        assertThat(response.transactionStatus()).isEqualTo(existingTransaction.getStatus());

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(ledgerEntryRepository);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void withdraw_amountEqualsBalance_succeeds() {
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(request.currency());
        when(ledgerEntryRepository.computeBalanceForAccount(account.getId())).thenReturn(request.amount()); // balance == amount
        when(accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, request.currency())).thenReturn(Optional.of(systemAccount));

        Transaction savedTransaction = new Transaction(TransactionType.WITHDRAWAL, TransactionStatus.PENDING, request.idempotencyKey());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        TransactionResponse response = transactionService.withdraw(request, CALLER_ID, false);

        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
    }


    @Test
    void withdraw_amountIsNotPositive() {
        CreateTransactionRequest zeroAmountRequest = new CreateTransactionRequest(IDEMPOTENCY_KEY, 0L, "USD", 1);
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.withdraw(zeroAmountRequest, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        verifyNoInteractions(accountRepository);
    }

    @Test
    void withdraw_destinationAccountDoesNotExists(){
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(request.accountId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.withdraw(request, CALLER_ID, false)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account does not exist");
    }

    @Test
    void withdraw_callerDoesNotOwnAccount() {
        User someoneElse = mock(User.class);
        when(someoneElse.getId()).thenReturn(999);
        when(account.getUser()).thenReturn(someoneElse);
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(request.accountId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> transactionService.withdraw(request, CALLER_ID, false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("does not belong to caller");
    }

    @Test
    void withdraw_callerWithdrawWithAdminRole() {
        User someoneElse = mock(User.class);
        lenient().when(someoneElse.getId()).thenReturn(999);
        lenient().when(account.getUser()).thenReturn(someoneElse);
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(request.currency());
        when(ledgerEntryRepository.computeBalanceForAccount(account.getId())).thenReturn(200L);
        when(accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, request.currency())).thenReturn(Optional.of(systemAccount));

        Transaction savedTransaction = new Transaction(TransactionType.WITHDRAWAL, TransactionStatus.PENDING, request.idempotencyKey());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        TransactionResponse response = transactionService.withdraw(request, CALLER_ID, true);

        assertThat(response).isNotNull();

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();

        LedgerEntry creditEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.CREDIT).findFirst().orElseThrow();
        LedgerEntry debitEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.DEBIT).findFirst().orElseThrow();

        assertThat(debitEntry.getAccount()).isEqualTo(account);
        assertThat(debitEntry.getAmount()).isEqualTo(100L);
        assertThat(creditEntry.getAccount()).isEqualTo(systemAccount);
        assertThat(creditEntry.getAmount()).isEqualTo(100L);
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(response.transactionType()).isEqualTo(TransactionType.WITHDRAWAL);
    }

    @Test
    void withdraw_destinationAccountIsNotActive() {
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.FROZEN);

        assertThatThrownBy(() -> transactionService.withdraw(request, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the account is not active");
    }

    @Test
    void withdraw_destinationAccountCurrencyMismatch() {
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn("CAD");

        assertThatThrownBy(() -> transactionService.withdraw(request, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void withdraw_balanceIsLessThanAmount() {
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(request.currency());
        when(ledgerEntryRepository.computeBalanceForAccount(account.getId())).thenReturn(50L);

        assertThatThrownBy(() -> transactionService.withdraw(request, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insufficient funds");
    }

    @Test
    void withdraw_noSystemAccountExistsForCurrency() {
        when(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(request.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(request.currency());
        when(ledgerEntryRepository.computeBalanceForAccount(account.getId())).thenReturn(500L);
        when(accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, request.currency())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.withdraw(request, CALLER_ID, false)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no system account is defined for this currency");
    }

    @Test
    void transfer_happyPath_postTransactionAndCreateTwoLedgerEntries() {
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.of(account));
        when(accountRepository.findByIdForUpdate(transferRequest.toAccountId())).thenReturn(Optional.of(transferToAccount));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(transferToAccount.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(transferRequest.currency());
        when(transferToAccount.getCurrency()).thenReturn(transferRequest.currency());
        when(account.getId()).thenReturn(1);
        when(ledgerEntryRepository.computeBalanceForAccount(transferRequest.fromAccountId())).thenReturn(200L);

        Transaction savedTransaction = new Transaction(TransactionType.TRANSFER, TransactionStatus.PENDING, transferRequest.idempotencyKey());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        TransactionResponse response = transactionService.transfer(transferRequest, CALLER_ID, false);

        assertThat(response).isNotNull();

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();

        LedgerEntry creditEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.CREDIT).findFirst().orElseThrow();
        LedgerEntry debitEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.DEBIT).findFirst().orElseThrow();

        ArgumentCaptor<TransactionPostedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionPostedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());

        assertThat(eventCaptor.getValue().type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(eventCaptor.getValue().status()).isEqualTo(TransactionStatus.POSTED);

        assertThat(debitEntry.getAccount()).isEqualTo(account);
        assertThat(creditEntry.getAccount()).isEqualTo(transferToAccount);
        assertThat(debitEntry.getAmount()).isEqualTo(transferRequest.amount());
        assertThat(creditEntry.getAmount()).isEqualTo(transferRequest.amount());
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(response.transactionType()).isEqualTo(TransactionType.TRANSFER);
    }

    @Test
    void transfer_idempotencyKeyAlreadyExists() {
        Transaction existingTransaction = new Transaction(TransactionType.TRANSFER, TransactionStatus.PENDING, transferRequest.idempotencyKey());
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.of(existingTransaction));

        TransactionResponse response = transactionService.transfer(transferRequest, CALLER_ID, false);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(existingTransaction.getIdempotencyKey());
        assertThat(response.transactionStatus()).isEqualTo(existingTransaction.getStatus());

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(ledgerEntryRepository);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void transfer_amountIsNotPositive() {
        TransferRequest zeroAmountRequest = new TransferRequest(transferRequest.idempotencyKey(), 0L, transferRequest.currency(), transferRequest.fromAccountId(), transferRequest.toAccountId());
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.transfer(zeroAmountRequest, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfer amount must be positive");

        verifyNoInteractions(accountRepository);
    }

    @Test
    void transfer_transferAccountToItself() {
        TransferRequest badRequest = new TransferRequest(
                transferRequest.idempotencyKey(),
                transferRequest.amount(),
                transferRequest.currency(),
                transferRequest.fromAccountId(),
                transferRequest.fromAccountId()
        );

        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.transfer(badRequest, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot transfer to the same account");

        verifyNoInteractions(accountRepository);
    }

    @Test
    void transfer_sourceAccountDoesNotExists() {
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.transfer(transferRequest, CALLER_ID, false)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account does not exist");
    }

    @Test
    void transfer_callerDoesNotOwnSourceAccount() {
        User someoneElse = mock(User.class);
        when(someoneElse.getId()).thenReturn(999);
        when(account.getUser()).thenReturn(someoneElse);
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.of(account));
        when(accountRepository.findByIdForUpdate(transferRequest.toAccountId())).thenReturn(Optional.of(transferToAccount));

        assertThatThrownBy(() -> transactionService.transfer(transferRequest, CALLER_ID, false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("does not belong to caller");
    }

    @Test
    void transfer_callerTransferWithAdminRole() {
        User someoneElse = mock(User.class);
        lenient().when(someoneElse.getId()).thenReturn(999);
        lenient().when(account.getUser()).thenReturn(someoneElse);
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.of(account));
        when(accountRepository.findByIdForUpdate(transferRequest.toAccountId())).thenReturn(Optional.of(transferToAccount));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(transferToAccount.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(transferRequest.currency());
        when(transferToAccount.getCurrency()).thenReturn(transferRequest.currency());
        when(account.getId()).thenReturn(1);
        when(ledgerEntryRepository.computeBalanceForAccount(transferRequest.fromAccountId())).thenReturn(200L);

        Transaction savedTransaction = new Transaction(TransactionType.TRANSFER, TransactionStatus.PENDING, transferRequest.idempotencyKey());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        TransactionResponse response = transactionService.transfer(transferRequest, CALLER_ID, true);

        assertThat(response).isNotNull();

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();

        LedgerEntry creditEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.CREDIT).findFirst().orElseThrow();
        LedgerEntry debitEntry = entries.stream().filter(e -> e.getDirection() == TransactionDirection.DEBIT).findFirst().orElseThrow();

        assertThat(debitEntry.getAccount()).isEqualTo(account);
        assertThat(creditEntry.getAccount()).isEqualTo(transferToAccount);
        assertThat(debitEntry.getAmount()).isEqualTo(transferRequest.amount());
        assertThat(creditEntry.getAmount()).isEqualTo(transferRequest.amount());
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(response.transactionType()).isEqualTo(TransactionType.TRANSFER);
    }

    @Test
    void transfer_destinationAccountDoesNotExists() {
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.of(account));
        when(accountRepository.findByIdForUpdate(transferRequest.toAccountId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.transfer(transferRequest, CALLER_ID, false)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account does not exist");
    }

    @Test
    void transfer_sourceAccountIsNotActive() {
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.of(account));
        when(accountRepository.findByIdForUpdate(transferRequest.toAccountId())).thenReturn(Optional.of(transferToAccount));
        when(account.getStatus()).thenReturn(AccountStatus.FROZEN);
        when(transferToAccount.getStatus()).thenReturn(AccountStatus.ACTIVE);

        assertThatThrownBy(() -> transactionService.transfer(transferRequest, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the account is not active");
    }

    @Test
    void transfer_destinationAccountIsNotActive() {
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.of(account));
        when(accountRepository.findByIdForUpdate(transferRequest.toAccountId())).thenReturn(Optional.of(transferToAccount));
        when(transferToAccount.getStatus()).thenReturn(AccountStatus.FROZEN);

        assertThatThrownBy(() -> transactionService.transfer(transferRequest, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the account is not active");
    }

    @Test
    void transfer_sourceAccountCurrencyMismatch() {
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.of(account));
        when(accountRepository.findByIdForUpdate(transferRequest.toAccountId())).thenReturn(Optional.of(transferToAccount));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(transferToAccount.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn("CAD");
        when(transferToAccount.getCurrency()).thenReturn(transferRequest.currency());

        assertThatThrownBy(() -> transactionService.transfer(transferRequest, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");

    }

    @Test
    void transfer_destinationAccountCurrencyMismatch() {
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.of(account));
        when(accountRepository.findByIdForUpdate(transferRequest.toAccountId())).thenReturn(Optional.of(transferToAccount));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(transferToAccount.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(transferRequest.currency());
        when(transferToAccount.getCurrency()).thenReturn("CAD");

        assertThatThrownBy(() -> transactionService.transfer(transferRequest, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");

    }

    @Test
    void transfer_amountEqualsBalance_succeeds() {
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.of(account));
        when(accountRepository.findByIdForUpdate(transferRequest.toAccountId())).thenReturn(Optional.of(transferToAccount));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(transferToAccount.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(transferRequest.currency());
        when(transferToAccount.getCurrency()).thenReturn(transferRequest.currency());
        when(account.getId()).thenReturn(1);
        when(ledgerEntryRepository.computeBalanceForAccount(transferRequest.fromAccountId())).thenReturn(transferRequest.amount()); // balance == amount

        Transaction savedTransaction = new Transaction(TransactionType.TRANSFER, TransactionStatus.PENDING, transferRequest.idempotencyKey());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        TransactionResponse response = transactionService.transfer(transferRequest, CALLER_ID, false);

        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    void transfer_balanceIsLessThanAmount() {
        when(transactionRepository.findByIdempotencyKey(transferRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(transferRequest.fromAccountId())).thenReturn(Optional.of(account));
        when(accountRepository.findByIdForUpdate(transferRequest.toAccountId())).thenReturn(Optional.of(transferToAccount));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(transferToAccount.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(transferRequest.currency());
        when(transferToAccount.getCurrency()).thenReturn(transferRequest.currency());
        when(account.getId()).thenReturn(1);
        when(ledgerEntryRepository.computeBalanceForAccount(transferRequest.fromAccountId())).thenReturn(10L);

        assertThatThrownBy(() -> transactionService.transfer(transferRequest, CALLER_ID, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insufficient funds");
    }

    @Test
    void reverse_happyPath_postedTransactionAndCreateLedgerEntryForAllEntriesWithFlipDirection() {
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, "test-idem-123");
        transaction.setId(reverseRequest.transactionId());
        when(account.getId()).thenReturn(1);
        LedgerEntry creditEntry = new LedgerEntry(transaction, account, TransactionDirection.CREDIT, 100L, "USD");
        LedgerEntry debitEntry = new LedgerEntry(transaction, systemAccount, TransactionDirection.DEBIT, 100L, "USD");
        List<LedgerEntry> ledgerEntries = new ArrayList<>(List.of(creditEntry, debitEntry));

        when(transactionRepository.findByIdempotencyKey(reverseRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(transactionRepository.findById(reverseRequest.transactionId())).thenReturn(Optional.of(transaction));
        when(ledgerEntryRepository.findByTransactionId(transaction.getId())).thenReturn(ledgerEntries);


        Transaction savedTransaction = new Transaction(TransactionType.REVERSAL, TransactionStatus.PENDING, reverseRequest.idempotencyKey());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        TransactionResponse response = transactionService.reverse(reverseRequest);

        assertThat(response).isNotNull();
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(response.transactionType()).isEqualTo(TransactionType.REVERSAL);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(ledgerEntries.size())).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();

        LedgerEntry flippedCredit = entries.stream().filter(e -> e.getAccount() == account).findFirst().orElseThrow();
        LedgerEntry flippedDebit = entries.stream().filter(e -> e.getAccount() == systemAccount).findFirst().orElseThrow();

        ArgumentCaptor<TransactionPostedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionPostedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());

        assertThat(eventCaptor.getValue().type()).isEqualTo(TransactionType.REVERSAL);
        assertThat(eventCaptor.getValue().status()).isEqualTo(TransactionStatus.POSTED);
        // The customer account (account) sits on the CREDIT side of the original deposit and the
        // DEBIT side of its reversal - systemAccount is filtered out on both, so it must still
        // resolve as the sole primary party regardless of which direction it ends up on.
        assertThat(eventCaptor.getValue().accountId()).isEqualTo(account.getId());
        assertThat(eventCaptor.getValue().userId()).isEqualTo(CALLER_ID);
        assertThat(eventCaptor.getValue().counterpartyAccountId()).isNull();
        assertThat(eventCaptor.getValue().counterpartyUserId()).isNull();

        assertThat(flippedCredit.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(flippedDebit.getDirection()).isEqualTo(TransactionDirection.CREDIT);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.REVERSED);

    }

    @Test
    void reverse_idempotencyKeyAlreadyExists() {
        Transaction existingTransaction = new Transaction(TransactionType.REVERSAL, TransactionStatus.PENDING, reverseRequest.idempotencyKey());
        when(transactionRepository.findByIdempotencyKey(reverseRequest.idempotencyKey())).thenReturn(Optional.of(existingTransaction));

        TransactionResponse response = transactionService.reverse(reverseRequest);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(reverseRequest.idempotencyKey());
        assertThat(response.transactionStatus()).isEqualTo(existingTransaction.getStatus());

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void reverse_transactionDoesNotExists() {
        when(transactionRepository.findByIdempotencyKey(reverseRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(transactionRepository.findById(reverseRequest.transactionId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.reverse(reverseRequest)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("transaction does not exist");
    }

    @Test
    void reverse_transactionTypeIsReversal() {
        Transaction transaction = new Transaction(TransactionType.REVERSAL, TransactionStatus.POSTED, "test-idem-123");
        when(transactionRepository.findByIdempotencyKey(reverseRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(transactionRepository.findById(reverseRequest.transactionId())).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.reverse(reverseRequest)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("it is a reversal transaction");
    }

    @Test
    void reverse_transactionAlreadyReversed() {
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.REVERSED, "test-idem-123");
        when(transactionRepository.findByIdempotencyKey(reverseRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(transactionRepository.findById(reverseRequest.transactionId())).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.reverse(reverseRequest)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already reversed");
    }

    @Test
    void reverse_transactionIsNotInPostedStatus() {
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.FAILED, "test-idem-123");
        when(transactionRepository.findByIdempotencyKey(reverseRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(transactionRepository.findById(reverseRequest.transactionId())).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.reverse(reverseRequest)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not in POSTED status");
    }


    @Test
    void getTransactionById_transactionExists() {
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, IDEMPOTENCY_KEY);
        List<LedgerEntry> entries = getMockLedgerEntries(transaction);
        when(ledgerEntryRepository.findByTransactionId(transaction.getId())).thenReturn(entries);
        when(transactionRepository.findById(1)).thenReturn(Optional.of(transaction));

        TransactionResponse response = transactionService.getTransactionById(1, CALLER_ID, false);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(response.transactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    void getTransactionById_transactionDoesNotExists() {
        when(transactionRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransactionById(1, CALLER_ID, false)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("transaction not found");
    }

    @Test
    void getTransactionById_callerDoesNotOwnTransaction() {
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, IDEMPOTENCY_KEY);
        List<LedgerEntry> entries = getMockLedgerEntries(transaction);
        when(ledgerEntryRepository.findByTransactionId(transaction.getId())).thenReturn(entries);
        when(transactionRepository.findById(1)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.getTransactionById(1, WRONG_CALLER_ID, false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("does not belong to caller");
    }

    @Test
    void getTransactionById_callerOwnsOnlyOneOfTwoAccounts() {
        Transaction transaction = new Transaction(TransactionType.TRANSFER, TransactionStatus.POSTED, IDEMPOTENCY_KEY);
        transaction.setId(1);
        Account otherAccount = mock(Account.class); // owned by someone else entirely
        User otherOwner = mock(User.class);
        when(otherOwner.getId()).thenReturn(999);
        when(otherAccount.getUser()).thenReturn(otherOwner);
        LedgerEntry debit = new LedgerEntry(transaction, account, TransactionDirection.DEBIT, 100L, "USD");
        LedgerEntry credit = new LedgerEntry(transaction, otherAccount, TransactionDirection.CREDIT, 100L, "USD"); // otherAccount's owner is someone else
        // credit (the non-owned account) listed first, so anyMatch is forced to evaluate past a
        // non-match before reaching debit - proves this checks "any" account, not just the first.
        when(ledgerEntryRepository.findByTransactionId(1)).thenReturn(List.of(credit, debit));
        when(transactionRepository.findById(1)).thenReturn(Optional.of(transaction));

        TransactionResponse response = transactionService.getTransactionById(1, CALLER_ID, false);

        assertThat(response).isNotNull();
    }


    @Test
    void getTransactionById_getTransactionWithAdmin() {
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, IDEMPOTENCY_KEY);
        when(transactionRepository.findById(1)).thenReturn(Optional.of(transaction));

        TransactionResponse response = transactionService.getTransactionById(1, WRONG_CALLER_ID, true);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(response.transactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    void getTransactionByIdempotencyKey_idempotencyKeyDoesNotExists() {
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransactionByIdempotencyKey(IDEMPOTENCY_KEY, CALLER_ID, false)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("idempotency key not found");
    }

    @Test
    void getTransactionByIdempotencyKey_idempotencyKeyExists() {
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, IDEMPOTENCY_KEY);
        List<LedgerEntry> entries = getMockLedgerEntries(transaction);
        when(ledgerEntryRepository.findByTransactionId(transaction.getId())).thenReturn(entries);
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(transaction));

        TransactionResponse response = transactionService.getTransactionByIdempotencyKey(IDEMPOTENCY_KEY, CALLER_ID, false);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(response.transactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    void getTransactionByIdempotencyKey_callerDoesNotOwnTransaction() {
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, IDEMPOTENCY_KEY);
        List<LedgerEntry> entries = getMockLedgerEntries(transaction);
        when(ledgerEntryRepository.findByTransactionId(transaction.getId())).thenReturn(entries);
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.getTransactionByIdempotencyKey(IDEMPOTENCY_KEY, WRONG_CALLER_ID, false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("does not belong to caller");
    }

    @Test
    void getTransactionByIdempotencyKey_getTransactionWithAdmin() {
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, IDEMPOTENCY_KEY);
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(transaction));

        TransactionResponse response = transactionService.getTransactionByIdempotencyKey(IDEMPOTENCY_KEY, WRONG_CALLER_ID, true);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(response.transactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    private List<LedgerEntry> getMockLedgerEntries(Transaction transaction) {
        transaction.setId(1);
        LedgerEntry debit = new LedgerEntry(transaction, account, TransactionDirection.DEBIT, 100L, "USD");
        LedgerEntry credit = new LedgerEntry(transaction, account, TransactionDirection.CREDIT, 100L, "USD");
         return List.of(debit, credit);
    }
}
