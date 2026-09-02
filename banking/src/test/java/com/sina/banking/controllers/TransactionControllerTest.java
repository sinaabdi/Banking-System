package com.sina.banking.controllers;

import com.sina.banking.DTOs.TransactionDTOs.*;
import com.sina.banking.models.*;
import com.sina.banking.security.AppUserPrincipal;
import com.sina.banking.services.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@Import({TransactionController.class, TransactionControllerTest.MethodSecurityTestConfig.class})
public class TransactionControllerTest {

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }

    private final Integer USER_ID = 1;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransactionService transactionService;


    private Authentication authAs(Integer id, UserRole role) {
        User user = new User("Test", "User", "testuser", "hash", "test@example.com");
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "role", role);
        ReflectionTestUtils.setField(user, "status", UserStatus.ACTIVE);
        AppUserPrincipal principal = new AppUserPrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void deposit_happyPath_return201() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest("idem-key-123", 100L, "USD", 1);
        TransactionResponse response = new TransactionResponse(1, TransactionType.DEPOSIT, TransactionStatus.POSTED, "idem-key-123", LocalDateTime.now(), LocalDateTime.now());

        when(transactionService.deposit(request, USER_ID, false)).thenReturn(response);

        mockMvc.perform(post("/api/transactions/deposit")
                .with(authentication(authAs(USER_ID, UserRole.USER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void withdraw_happyPath_return201() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest("idem-key-123", 100L, "USD", 1);
        TransactionResponse response = new TransactionResponse(1, TransactionType.DEPOSIT, TransactionStatus.POSTED, "idem-key-123", LocalDateTime.now(), LocalDateTime.now());

        when(transactionService.withdraw(request, USER_ID, false)).thenReturn(response);

        mockMvc.perform(post("/api/transactions/withdraw")
                        .with(authentication(authAs(USER_ID, UserRole.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void transfer_happyPath_return201() throws Exception {
        TransferRequest request = new TransferRequest("idem-key-123", 100L, "USD", USER_ID, 99);
        TransactionResponse response = new TransactionResponse(1, TransactionType.TRANSFER, TransactionStatus.POSTED, "idem-key-123", LocalDateTime.now(), LocalDateTime.now());

        when(transactionService.transfer(request, USER_ID, false)).thenReturn(response);

        mockMvc.perform(post("/api/transactions/transfer")
                .with(authentication(authAs(USER_ID, UserRole.USER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

    }

    @Test
    void reverse_asNonAdmin_return403() throws Exception {
        ReverseTransactionRequest request = new ReverseTransactionRequest("idem-key-123", 1);
        TransactionResponse response = new TransactionResponse(1, TransactionType.REVERSAL, TransactionStatus.POSTED, "idem-key-123", LocalDateTime.now(), LocalDateTime.now());

        when(transactionService.reverse(request)).thenReturn(response);

        mockMvc.perform(post("/api/transactions/reverse")
                        .with(authentication(authAs(USER_ID, UserRole.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reverse_asAdmin_return201() throws Exception {
        ReverseTransactionRequest request = new ReverseTransactionRequest("idem-key-123", 1);
        TransactionResponse response = new TransactionResponse(1, TransactionType.REVERSAL, TransactionStatus.POSTED, "idem-key-123", LocalDateTime.now(), LocalDateTime.now());

        when(transactionService.reverse(request)).thenReturn(response);

        mockMvc.perform(post("/api/transactions/reverse")
                        .with(authentication(authAs(USER_ID, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void getTransactionById_happyPath_return200() throws Exception {
        mockMvc.perform(get("/api/transactions/1")
                .with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isOk());
    }

    @Test
    void getTransactionByIdempotencyKey_happyPath_return200() throws Exception {
        mockMvc.perform(get("/api/transactions/by-idempotency-key/idem-key-123")
                        .with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isOk());
    }
}
