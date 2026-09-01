package com.sina.banking.controllers;

import com.sina.banking.DTOs.AccountDTOs.CreateAccountRequest;
import com.sina.banking.DTOs.AccountDTOs.AccountResponse;
import com.sina.banking.models.*;
import com.sina.banking.security.AppUserPrincipal;
import com.sina.banking.services.AccountService;
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


@WebMvcTest
@Import({AccountController.class, AccountControllerTest.MethodSecurityTestConfig.class})
public class AccountControllerTest {

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
    }

    private final Integer USER_ID = 1;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountService accountService;

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
    void createAccount_forOwn_return201() throws Exception {
        AccountResponse response = new AccountResponse(1, USER_ID, 1000L, AccountType.CHECKING, "USD", AccountStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        CreateAccountRequest request = new CreateAccountRequest(USER_ID, AccountType.CHECKING, "USD");

        when(accountService.createAccount(request)).thenReturn(response);

        mockMvc.perform(post("/api/accounts")
                .with(authentication(authAs(USER_ID, UserRole.USER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void createAccount_forSomeoneElse_return403() throws Exception {
        Integer someoneElseUserId = 99;
        CreateAccountRequest request = new CreateAccountRequest(someoneElseUserId, AccountType.CHECKING, "USD");

        mockMvc.perform(post("/api/accounts")
                .with(authentication(authAs(USER_ID, UserRole.USER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

    }

    @Test
    void createAccount_asAdmin_return201() throws Exception {
        Integer someoneElseUserId = 99;
        AccountResponse response = new AccountResponse(1, someoneElseUserId, 1000L, AccountType.CHECKING, "USD", AccountStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        CreateAccountRequest request = new CreateAccountRequest(someoneElseUserId, AccountType.CHECKING, "USD");

        when(accountService.createAccount(request)).thenReturn(response);

        mockMvc.perform(post("/api/accounts")
                        .with(authentication(authAs(USER_ID, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void getAccountById_happyPath_return200() throws Exception {
        mockMvc.perform(get("/api/accounts/1")
                .with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isOk());
    }

    @Test
    void getAccountByAccountNumber_happyPath_return200() throws Exception {
        mockMvc.perform(get("/api/accounts/by-number/123")
                        .with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isOk());
    }

    @Test
    void getAccountsForUser_ownProfile_return200() throws Exception {
        AccountResponse firstResponse = new AccountResponse(1, USER_ID, 1000L, AccountType.CHECKING, "USD", AccountStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        AccountResponse secondResponse = new AccountResponse(2, USER_ID, 1000L, AccountType.SAVINGS, "USD", AccountStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        List<AccountResponse> responses = List.of(firstResponse, secondResponse);

        when(accountService.getAccountsForUser(USER_ID)).thenReturn(responses);

        mockMvc.perform(get("/api/accounts/by-user/1").with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isOk());


    }

    @Test
    void getAccountsForUser_someoneElseProfile_return403() throws Exception {
       mockMvc.perform(get("/api/accounts/by-user/2").with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAccountsForUser_asAdmin_return200() throws Exception {
        AccountResponse firstResponse = new AccountResponse(1, USER_ID, 1000L, AccountType.CHECKING, "USD", AccountStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        AccountResponse secondResponse = new AccountResponse(2, USER_ID, 1000L, AccountType.SAVINGS, "USD", AccountStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        List<AccountResponse> responses = List.of(firstResponse, secondResponse);

        when(accountService.getAccountsForUser(USER_ID)).thenReturn(responses);

        mockMvc.perform(get("/api/accounts/by-user/2").with(authentication(authAs(USER_ID, UserRole.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void freezeAccount_asAdmin_return204() throws Exception {
        mockMvc.perform(post("/api/accounts/2/freeze").with(authentication(authAs(USER_ID, UserRole.ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void freezeAccount_nonAdmin_return403() throws Exception {
        mockMvc.perform(post("/api/accounts/2/freeze").with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void closeAccount_asAdmin_return204() throws Exception {
        mockMvc.perform(post("/api/accounts/2/close").with(authentication(authAs(USER_ID, UserRole.ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void closeAccount_nonAdmin_return403() throws Exception {
        mockMvc.perform(post("/api/accounts/2/close").with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void activeAccount_asAdmin_return204() throws Exception {
        mockMvc.perform(post("/api/accounts/2/active").with(authentication(authAs(USER_ID, UserRole.ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void activeAccount_nonAdmin_return403() throws Exception {
        mockMvc.perform(post("/api/accounts/2/active").with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isForbidden());
    }
}
