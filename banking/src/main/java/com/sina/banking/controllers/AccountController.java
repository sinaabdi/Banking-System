package com.sina.banking.controllers;

import com.sina.banking.DTOs.AccountDTOs.CreateAccountRequest;
import com.sina.banking.DTOs.AccountDTOs.AccountResponse;
import com.sina.banking.security.AppUserPrincipal;
import com.sina.banking.services.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@Tag(name = "Accounts")
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @Operation(summary = "Create an account for a user")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    @PreAuthorize("#request.userId() == authentication.principal.id or hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@RequestBody CreateAccountRequest request) {
        log.debug("POST /api/accounts userId={} type={}", request.userId(), request.type());
        AccountResponse accountResponse = accountService.createAccount(request);
        return ResponseEntity.created(URI.create("/api/accounts/" + accountResponse.id())).body(accountResponse);
    }

    @Operation(summary = "Get an account by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "404", description = "No account with this id")
    })
    @GetMapping("/{id}")
    public AccountResponse getAccountById(@PathVariable Integer id, @AuthenticationPrincipal AppUserPrincipal principal) {
            log.debug("GET /api/accounts/{}", id);
        return accountService.getAccountByAccountId(id, principal.getId(), principal.isAdmin());
    }

    @Operation(summary = "Get an account by its account number")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "404", description = "No account with this account number")
    })
    @GetMapping("/by-number/{accountNumber}")
    public AccountResponse getAccountByAccountNumber(@PathVariable Long accountNumber, @AuthenticationPrincipal AppUserPrincipal principal) {
        log.debug("GET /api/accounts/by-number/{}", accountNumber);
        return accountService.getAccountByAccountNumber(accountNumber, principal.getId(), principal.isAdmin());
    }

    @Operation(summary = "List all accounts belonging to a user")
    @ApiResponse(responseCode = "200", description = "List of accounts (possibly empty)")
    @PreAuthorize("#userId == authentication.principal.id or hasRole('ADMIN')")
    @GetMapping("/by-user/{userId}")
    public List<AccountResponse> getAccountsForUser(@PathVariable Integer userId) {
        log.debug("GET /api/accounts/by-user/{}", userId);
        return accountService.getAccountsForUser(userId);
    }

    @Operation(summary = "Freeze an account, blocking deposits, withdrawals, and transfers on it")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account frozen"),
            @ApiResponse(responseCode = "404", description = "No account with this id")
    })
    @PostMapping("/{id}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void freezeAccount(@PathVariable Integer id) {
        log.debug("POST /api/accounts/{}/freeze", id);
        accountService.freezeAccount(id);
    }

    @Operation(summary = "Close an account")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account closed"),
            @ApiResponse(responseCode = "404", description = "No account with this id")
    })
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeAccount(@PathVariable Integer id) {
        log.debug("POST /api/accounts/{}/close", id);
        accountService.closeAccount(id);
    }

    @Operation(summary = "Reactivate a frozen or closed account")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account activated"),
            @ApiResponse(responseCode = "404", description = "No account with this id")
    })
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activeAccount(@PathVariable Integer id) {
        log.debug("POST /api/accounts/{}/active", id);
        accountService.activeAccount(id);
    }
}
