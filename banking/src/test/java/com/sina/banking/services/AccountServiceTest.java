package com.sina.banking.services;

import com.sina.banking.DTOs.AccountDTOs.*;
import com.sina.banking.models.Account;
import com.sina.banking.models.AccountStatus;
import com.sina.banking.models.AccountType;
import com.sina.banking.models.User;
import com.sina.banking.repositories.AccountRepository;
import com.sina.banking.repositories.LedgerEntryRepository;
import com.sina.banking.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @InjectMocks
    private AccountService accountService;

    private CreateAccountRequest accountRequest;

    @BeforeEach
    void setup() {
        accountRequest = new CreateAccountRequest(
                1,
                AccountType.CHECKING,
                "USD"
        );
    }

    @Test
    void createAccount_successfullyCreateAccount() {
        User user = new User("John", "Doe", "testUser", "@#$%123", "testuser@bank.local");
        when(userRepository.findById(accountRequest.userId())).thenReturn(Optional.of(user));
        when(accountRepository.nextAccountNumberSeed()).thenReturn(1000000000L);
        when(accountRepository.save(any(Account.class))).then(invocation -> invocation.getArgument(0));


        AccountResponse response = accountService.createAccount(accountRequest);
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.currency()).isEqualTo(accountRequest.currency());
        assertThat(response.accountNumber()).isEqualTo(10000000009L);
    }

    @Test
    void createAccount_userNotFound() {
        when(userRepository.findById(accountRequest.userId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> accountService.createAccount(accountRequest)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("user not found");
    }

    @Test
    void freezeAccount_successfullyFreezeAccount() {
        Account account = getMockAccount();

        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        accountService.freezeAccount(1);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    void freezeAccount_accountNotFound() {
        when(accountRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.freezeAccount(1)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account not found");
    }

    @Test
    void closeAccount_successfullyCloseAccount() {
        Account account = getMockAccount();
        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        accountService.closeAccount(1);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    void closeAccount_accountNotFound() {
        when(accountRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.closeAccount(1)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account not found");
    }

    @Test
    void activeAccount_successfullyActiveAccount() {
        Account account = getMockAccount();
        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        accountService.activeAccount(1);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void activeAccount_accountNotFound() {
        when(accountRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.activeAccount(1)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account not found");
    }

    @Test
    void getAccountByAccountId_successfullyGetAccount() {
        Account account = getMockAccount();
        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        AccountResponse response = accountService.getAccountByAccountId(1);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(account.getStatus());
        assertThat(response.userId()).isEqualTo(account.getUser().getId());
    }

    @Test
    void getAccountByAccountId_accountNotFound() {
        when(accountRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccountByAccountId(1)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account not found");
    }

    @SuppressWarnings("null")
    @Test
    void getAccountsForUser_getListOfAccounts() {
        Account account = getMockAccount();
        Account anotherAccount = new Account(account.getUser(), 100000L, AccountType.CHECKING, "EUR");
        List<Account> accountList = List.of(account, anotherAccount);

        when(accountRepository.findByUserId(1)).thenReturn(accountList);

        List<AccountResponse> responseList = accountService.getAccountsForUser(1);

        assertThat(responseList).isNotNull();
        assertThat(responseList.size()).isEqualTo(accountList.size());
        assertThat(responseList).extracting(AccountResponse::currency)
                .containsExactlyInAnyOrder(account.getCurrency(), anotherAccount.getCurrency());
    }

    @Test
    void getAccountByAccountNumber_successfullyGetAccount() {
        Account account = getMockAccount();
        when(accountRepository.findByAccountNumber(123L)).thenReturn(Optional.of(account));

        AccountResponse response = accountService.getAccountByAccountNumber(123L);

        assertThat(response).isNotNull();
        assertThat(response.accountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(response.currency()).isEqualTo(account.getCurrency());
        assertThat(response.userId()).isEqualTo(account.getUser().getId());
    }

    @Test
    void getAccountByAccountNumber_accountNotFound() {
        when(accountRepository.findByAccountNumber(123L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccountByAccountNumber(123L)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account not found");
    }

    @Test
    void getBalance_accountNotFound() {
        when(accountRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getBalance(1)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("account not found");

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void getBalance_successfullyGetBalance() {
        Account account = getMockAccount();
        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        when(ledgerEntryRepository.computeBalanceForAccount(1)).thenReturn(100L);

        Long balance = accountService.getBalance(1);

        assertThat(balance).isNotNull();
        assertThat(balance).isEqualTo(100L);
    }

    private Account getMockAccount() {
        User user = new User("John", "Doe", "testUser", "@#$%123", "testuser@bank.local");
        return new Account(user, 100000L, AccountType.CHECKING, "USD");
    }
}
