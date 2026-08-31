package com.sina.banking.services;

import com.sina.banking.DTOs.AuthDTOs.LoginRequest;
import com.sina.banking.DTOs.AuthDTOs.LoginResponse;
import com.sina.banking.security.AppUserDetailsService;
import com.sina.banking.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AuthenticationServiceTest {
    @Mock
    AuthenticationManager authenticationManager;
    @Mock
    AppUserDetailsService userDetailsService;
    @Mock
    JwtService jwtService;

    @InjectMocks
    AuthenticationService authenticationService;

    private LoginRequest loginRequest;

    @BeforeEach
    void setup() {
        loginRequest = new LoginRequest("test", "password");
    }

    @Test
    void login_successfulLogin() {
        String fakeToken = "fake.jwt.token";
        UserDetails userDetails = mock(UserDetails.class);
        when(userDetailsService.loadUserByUsername(loginRequest.username())).thenReturn(userDetails);
        when(jwtService.generateJwtToken(userDetails)).thenReturn(fakeToken);

        LoginResponse response = authenticationService.login(loginRequest);

        assertThat(response.token()).isEqualTo(fakeToken);
    }

    @Test
    void login_authenticationFails() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad credentials"));

        assertThatThrownBy(() -> authenticationService.login(loginRequest))
                .isInstanceOf(BadCredentialsException.class);

        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(jwtService, never()).generateJwtToken(any());
    }
}
