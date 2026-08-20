package com.sina.banking.controllers;

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
        return ResponseEntity.created(URI.create("/api/transactions/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public TransactionResponse getTransactionById(@PathVariable Integer id) {
        return transactionService.getTransactionById(id);
    }
}
