package com.sina.banking.controllers;

import com.sina.banking.DTOs.AccountDtos.CreateAccountRequest;
import com.sina.banking.DTOs.AccountDtos.AccountResponse;
import com.sina.banking.services.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@RequestBody CreateAccountRequest request) {
        AccountResponse accountResponse = accountService.createAccount(request);
        return ResponseEntity.created(URI.create("/api/accounts/" + accountResponse.id())).body(accountResponse);
    }

    @GetMapping("/{id}")
    public AccountResponse getAccountById(@PathVariable Integer id) {
        return accountService.getAccountByAccountId(id);
    }

    @GetMapping("/by-number/{accountNumber}")
    public AccountResponse getAccountByAccountNumber(@PathVariable Long accountNumber) {
        return accountService.getAccountByAccountNumber(accountNumber);
    }

    @GetMapping("/by-user/{userId}")
    public List<AccountResponse> getAccountsForUser(@PathVariable Integer userId) {
        return accountService.getAccountsForUser(userId);
    }

    @PostMapping("/{id}/freeze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void freezeAccount(@PathVariable Integer id) {
        accountService.freezeAccount(id);
    }

    @PostMapping("/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeAccount(@PathVariable Integer id) {
        accountService.closeAccount(id);
    }

    @PostMapping("/{id}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activeAccount(@PathVariable Integer id) {
        accountService.activeAccount(id);
    }
}
