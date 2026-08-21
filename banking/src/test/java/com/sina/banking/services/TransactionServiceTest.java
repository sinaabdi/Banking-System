package com.sina.banking.services;

import com.sina.banking.DTOs.TransactionDtos.TransactionResponse;
import com.sina.banking.DTOs.TransactionDtos.CreateTransactionRequest;
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

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransactionServiceTest {
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private TransactionService transactionService;

    private Account account;
    private Account systemAccount;
    private CreateTransactionRequest depositRequest;

    @BeforeEach
    void setup(){
        account = mock(Account.class);
        systemAccount = mock(Account.class);
        depositRequest = new CreateTransactionRequest(
                "idem-key-123",
                100L,
                "USD",
                1);
    }

    @Test
    void deposit_happyPath_postsTransactionAndCreatesTwoLedgerEntries(){
        when(transactionRepository.findByIdempotencyKey(depositRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(depositRequest.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(depositRequest.currency());
        when(accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, depositRequest.currency())).thenReturn(Optional.of(systemAccount));

        Transaction savedTransaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.PENDING, depositRequest.idempotencyKey());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        TransactionResponse response = transactionService.deposit(depositRequest);

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
    void deposit_idempotencyKeyAlreadyExists() {
        Transaction existingTransaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.PENDING, depositRequest.idempotencyKey());
        when(transactionRepository.findByIdempotencyKey(depositRequest.idempotencyKey())).thenReturn(Optional.of(existingTransaction));

        TransactionResponse response = transactionService.deposit(depositRequest);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(existingTransaction.getIdempotencyKey());
        assertThat(response.transactionStatus()).isEqualTo(existingTransaction.getStatus());

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(ledgerEntryRepository);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void deposit_amountIsNotPositive() {
        CreateTransactionRequest zeroAmountRequest = new CreateTransactionRequest("idem-key-123", 0L, "USD", 1);
        when(transactionRepository.findByIdempotencyKey(depositRequest.idempotencyKey())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.deposit(zeroAmountRequest)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        verifyNoInteractions(accountRepository);
    }

    @Test
    void deposit_destinationAccountDoesNotExists(){
        when(transactionRepository.findByIdempotencyKey(depositRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(depositRequest.accountId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.deposit(depositRequest)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account does not exists");
    }

    @Test
    void deposit_destinationAccountIsNotActive() {
        when(transactionRepository.findByIdempotencyKey(depositRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(depositRequest.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.FROZEN);

        assertThatThrownBy(() -> transactionService.deposit(depositRequest)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the account is not active");
    }

    @Test
    void deposit_destinationAccountCurrencyMismatch() {
        when(transactionRepository.findByIdempotencyKey(depositRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(depositRequest.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn("CAD");

        assertThatThrownBy(() -> transactionService.deposit(depositRequest)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void deposit_noSystemAccountExistsForCurrency() {
        when(transactionRepository.findByIdempotencyKey(depositRequest.idempotencyKey())).thenReturn(Optional.empty());
        when(accountRepository.findById(depositRequest.accountId())).thenReturn(Optional.of(account));
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn(depositRequest.currency());
        when(accountRepository.findAccountByTypeAndCurrency(AccountType.SYSTEM, depositRequest.currency())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.deposit(depositRequest)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no system account is defined for this currency");
    }

    @Test
    void getTransactionById_transactionDoesNotExists() {
        when(transactionRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransactionById(1)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("transaction not found");
    }

    @Test
    void getTransactionById_transactionExists() {
        String idemKey = "idem-key-123";
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, idemKey);
        when(transactionRepository.findById(1)).thenReturn(Optional.of(transaction));

        TransactionResponse response = transactionService.getTransactionById(1);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(idemKey);
        assertThat(response.transactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    void getTransactionByIdempotencyKey_idempotencyKeyDoesNotExists() {
        String idemKey = "idem-key-123";
        when(transactionRepository.findByIdempotencyKey(idemKey)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransactionByIdempotencyKey(idemKey)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("idempotency key not found");
    }

    @Test
    void getTransactionByIdempotencyKey_idempotencyKeyExists() {
        String idemKey = "idem-key-123";
        Transaction transaction = new Transaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, idemKey);

        when(transactionRepository.findByIdempotencyKey(idemKey)).thenReturn(Optional.of(transaction));

        TransactionResponse response = transactionService.getTransactionByIdempotencyKey(idemKey);

        assertThat(response).isNotNull();
        assertThat(response.idempotencyKey()).isEqualTo(idemKey);
        assertThat(response.transactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
    }
}
