package com.sina.banking.controllers;

import com.sina.banking.DTOs.TransactionDtos.TransferRequest;
import com.sina.banking.DTOs.TransactionDtos.CreateTransactionRequest;
import com.sina.banking.DTOs.TransactionDtos.TransactionResponse;
import com.sina.banking.services.TransactionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(@RequestBody CreateTransactionRequest request) {
        TransactionResponse response = transactionService.deposit(request);
        return ResponseEntity.created(URI.create("/api/transactions/deposit/" + response.id())).body(response);
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(@RequestBody CreateTransactionRequest request) {
        TransactionResponse response = transactionService.withdraw(request);
        return ResponseEntity.created(URI.create("/api/transactions/withdraw/" + response.id())).body(response);
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(@RequestBody TransferRequest request) {
        TransactionResponse response = transactionService.transfer(request);
        return ResponseEntity.created(URI.create("/api/transactions/transfer/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public TransactionResponse getTransactionById(@PathVariable Integer id) {
        return transactionService.getTransactionById(id);
    }

    @GetMapping("/by-idempotency-key/{idempotencyKey}")
    public TransactionResponse getTransactionByIdempotencyKey(@PathVariable String idempotencyKey) {
        return transactionService.getTransactionByIdempotencyKey(idempotencyKey);
    }
}
