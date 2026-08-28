package com.sina.banking.controllers;

import com.sina.banking.DTOs.TransactionDtos.TransferRequest;
import com.sina.banking.DTOs.TransactionDtos.CreateTransactionRequest;
import com.sina.banking.DTOs.TransactionDtos.TransactionResponse;
import com.sina.banking.services.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(@RequestBody CreateTransactionRequest request) {
        log.debug("POST /api/transactions/deposit accountId={} amount={}", request.accountId(), request.amount());
        TransactionResponse response = transactionService.deposit(request);
        return ResponseEntity.created(URI.create("/api/transactions/deposit/" + response.id())).body(response);
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(@RequestBody CreateTransactionRequest request) {
        log.debug("POST /api/transactions/withdraw accountId={} amount={}", request.accountId(), request.amount());
        TransactionResponse response = transactionService.withdraw(request);
        return ResponseEntity.created(URI.create("/api/transactions/withdraw/" + response.id())).body(response);
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(@RequestBody TransferRequest request) {
        log.debug("POST /api/transactions/transfer fromAccountId={} toAccountId={} amount={}",
                request.fromAccountId(), request.toAccountId(), request.amount());
        TransactionResponse response = transactionService.transfer(request);
        return ResponseEntity.created(URI.create("/api/transactions/transfer/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public TransactionResponse getTransactionById(@PathVariable Integer id) {
        log.debug("GET /api/transactions/{}", id);
        return transactionService.getTransactionById(id);
    }

    @GetMapping("/by-idempotency-key/{idempotencyKey}")
    public TransactionResponse getTransactionByIdempotencyKey(@PathVariable String idempotencyKey) {
        log.debug("GET /api/transactions/by-idempotency-key/{}", idempotencyKey);
        return transactionService.getTransactionByIdempotencyKey(idempotencyKey);
    }
}
