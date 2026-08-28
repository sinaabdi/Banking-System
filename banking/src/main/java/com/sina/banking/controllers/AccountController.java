package com.sina.banking.controllers;

import com.sina.banking.DTOs.AccountDtos.CreateAccountRequest;
import com.sina.banking.DTOs.AccountDtos.AccountResponse;
import com.sina.banking.services.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@RequestBody CreateAccountRequest request) {
        log.debug("POST /api/accounts userId={} type={}", request.userId(), request.type());
        AccountResponse accountResponse = accountService.createAccount(request);
        return ResponseEntity.created(URI.create("/api/accounts/" + accountResponse.id())).body(accountResponse);
    }

    @GetMapping("/{id}")
    public AccountResponse getAccountById(@PathVariable Integer id) {
        log.debug("GET /api/accounts/{}", id);
        return accountService.getAccountByAccountId(id);
    }

    @GetMapping("/by-number/{accountNumber}")
    public AccountResponse getAccountByAccountNumber(@PathVariable Long accountNumber) {
        log.debug("GET /api/accounts/by-number/{}", accountNumber);
        return accountService.getAccountByAccountNumber(accountNumber);
    }

    @GetMapping("/by-user/{userId}")
    public List<AccountResponse> getAccountsForUser(@PathVariable Integer userId) {
        log.debug("GET /api/accounts/by-user/{}", userId);
        return accountService.getAccountsForUser(userId);
    }

    @PostMapping("/{id}/freeze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void freezeAccount(@PathVariable Integer id) {
        log.debug("POST /api/accounts/{}/freeze", id);
        accountService.freezeAccount(id);
    }

    @PostMapping("/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeAccount(@PathVariable Integer id) {
        log.debug("POST /api/accounts/{}/close", id);
        accountService.closeAccount(id);
    }

    @PostMapping("/{id}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activeAccount(@PathVariable Integer id) {
        log.debug("POST /api/accounts/{}/active", id);
        accountService.activeAccount(id);
    }
}
