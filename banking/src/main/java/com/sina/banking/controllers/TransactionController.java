package com.sina.banking.controllers;

import com.sina.banking.DTOs.TransactionDTOs.ReverseTransactionRequest;
import com.sina.banking.DTOs.TransactionDTOs.TransferRequest;
import com.sina.banking.DTOs.TransactionDTOs.CreateTransactionRequest;
import com.sina.banking.DTOs.TransactionDTOs.TransactionResponse;
import com.sina.banking.security.AppUserPrincipal;
import com.sina.banking.services.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@Tag(name = "Transactions")
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Operation(summary = "Deposit an amount into an account")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Deposit posted (or the original result, if this idempotency key was already processed)"),
            @ApiResponse(responseCode = "400", description = "Amount is not positive, account is not active, or currency does not match the account"),
            @ApiResponse(responseCode = "404", description = "Account does not exist, or no system account is configured for this currency")
    })
    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(@RequestBody CreateTransactionRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        log.debug("POST /api/transactions/deposit accountId={} amount={}", request.accountId(), request.amount());
        TransactionResponse response = transactionService.deposit(request, principal.getId(), principal.isAdmin());
        return ResponseEntity.created(URI.create("/api/transactions/deposit/" + response.id())).body(response);
    }

    @Operation(summary = "Withdraw an amount from an account, rejected if it exceeds the current balance")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Withdrawal posted (or the original result, if this idempotency key was already processed)"),
            @ApiResponse(responseCode = "400", description = "Amount is not positive, account is not active, currency does not match the account, or the balance is insufficient"),
            @ApiResponse(responseCode = "404", description = "Account does not exist, or no system account is configured for this currency")
    })
    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(@RequestBody CreateTransactionRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        log.debug("POST /api/transactions/withdraw accountId={} amount={}", request.accountId(), request.amount());
        TransactionResponse response = transactionService.withdraw(request, principal.getId(), principal.isAdmin());
        return ResponseEntity.created(URI.create("/api/transactions/withdraw/" + response.id())).body(response);
    }

    @Operation(summary = "Transfer an amount from one account to another")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transfer posted (or the original result, if this idempotency key was already processed)"),
            @ApiResponse(responseCode = "400", description = "Amount is not positive, source and destination are the same account, either account is not active, currency does not match either account, or the source balance is insufficient"),
            @ApiResponse(responseCode = "404", description = "Either account does not exist")
    })
    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(@RequestBody TransferRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        log.debug("POST /api/transactions/transfer fromAccountId={} toAccountId={} amount={}",
                request.fromAccountId(), request.toAccountId(), request.amount());
        TransactionResponse response = transactionService.transfer(request, principal.getId(), principal.isAdmin());
        return ResponseEntity.created(URI.create("/api/transactions/transfer/" + response.id())).body(response);
    }

    @Operation(summary = "Reverse a posted transaction by mirroring its ledger entries with the opposite direction")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reversal posted (or the original result, if this idempotency key was already processed)"),
            @ApiResponse(responseCode = "400", description = "The transaction is itself a reversal, has already been reversed, or is not in POSTED status"),
            @ApiResponse(responseCode = "404", description = "No transaction with this id")
    })
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/reverse")
    public ResponseEntity<TransactionResponse> reverse(@RequestBody ReverseTransactionRequest request) {
        log.debug("POST /api/transaction/reverse transaction id={}", request.transactionId());
        TransactionResponse response = transactionService.reverse(request);
        return ResponseEntity.created(URI.create("/api/transactions/reverse/" + response.id())).body(response);
    }

    @Operation(summary = "Get a transaction by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction found"),
            @ApiResponse(responseCode = "404", description = "No transaction with this id")
    })
    @GetMapping("/{id}")
    public TransactionResponse getTransactionById(@PathVariable Integer id) {
        log.debug("GET /api/transactions/{}", id);
        return transactionService.getTransactionById(id);
    }

    @Operation(summary = "Get a transaction by its idempotency key")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction found"),
            @ApiResponse(responseCode = "404", description = "No transaction with this idempotency key")
    })
    @GetMapping("/by-idempotency-key/{idempotencyKey}")
    public TransactionResponse getTransactionByIdempotencyKey(@PathVariable String idempotencyKey) {
        log.debug("GET /api/transactions/by-idempotency-key/{}", idempotencyKey);
        return transactionService.getTransactionByIdempotencyKey(idempotencyKey);
    }
}
