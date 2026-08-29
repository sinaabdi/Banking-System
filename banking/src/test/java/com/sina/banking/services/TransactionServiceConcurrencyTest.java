package com.sina.banking.services;

import com.sina.banking.DTOs.AccountDTOs.AccountResponse;
import com.sina.banking.DTOs.AccountDTOs.CreateAccountRequest;
import com.sina.banking.DTOs.TransactionDTOs.TransactionResponse;
import com.sina.banking.DTOs.TransactionDTOs.CreateTransactionRequest;
import com.sina.banking.DTOs.UserDTOs.UserResponse;
import com.sina.banking.DTOs.UserDTOs.CreateUserRequest;
import com.sina.banking.models.AccountType;
import com.sina.banking.models.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class TransactionServiceConcurrencyTest {
    @Autowired
    private UserService userService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TransactionService transactionService;

    private Integer accountId;

    private static final long STARTING_BALANCE = 500L;
    private static final int THREAD_COUNT = 10;
    private static final long AMOUNT_PER_WITHDRAWAL = 100L;

    @BeforeEach
    void seedAccountWithKnownBalance() {
        // Create test user
        String usernameSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String username = "bankuser-" + usernameSuffix;
        CreateUserRequest userRequest = new CreateUserRequest(
                "test",
                "user",
                username,
                "123456",
                username + "@bank.local"
        );
        UserResponse userResponse = userService.createUser(userRequest);

        // Create an account for the test user
        CreateAccountRequest accountRequest = new CreateAccountRequest(userResponse.id(), AccountType.CHECKING, "USD");
        AccountResponse accountResponse = accountService.createAccount(accountRequest);
        accountId = accountResponse.id();

        // Deposit 500L to the account
        String idempotencyKey = UUID.randomUUID().toString();
        CreateTransactionRequest depositRequest = new CreateTransactionRequest(idempotencyKey, STARTING_BALANCE, "USD", accountResponse.id());
        TransactionResponse transactionResponse = transactionService.deposit(depositRequest);

        assertThat(transactionResponse.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    void concurrentWithdrawals_neverExceedAvailableBalance() throws InterruptedException {

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<TransactionResponse>> futures = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            String idempotencyKey = UUID.randomUUID().toString().replace("-", "").substring(5);
            futures.add(executor.submit(() -> {
                startLatch.await();
                CreateTransactionRequest request = new CreateTransactionRequest(idempotencyKey, AMOUNT_PER_WITHDRAWAL, "USD", accountId);
                return transactionService.withdraw(request);
            }));
        }

        startLatch.countDown();
        executor.shutdown();

        int successCount = 0;

        for (Future<TransactionResponse> future : futures) {
            try {
                future.get();
                successCount++;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                boolean isInsufficientFunds = cause instanceof IllegalArgumentException && cause.getMessage().contains("insufficient funds");
                if (!isInsufficientFunds) {
                    throw new RuntimeException("Unexpected failure during concurrent withdrawal", cause);
                }
            }
        }

        long finalBalance = accountService.getBalance(accountId);
        assertThat(finalBalance).isEqualTo(STARTING_BALANCE - successCount * AMOUNT_PER_WITHDRAWAL);
    }

}
