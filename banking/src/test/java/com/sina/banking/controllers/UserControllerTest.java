package com.sina.banking.controllers;

import com.sina.banking.DTOs.UserDTOs.CreateUserRequest;
import com.sina.banking.DTOs.UserDTOs.ChangePasswordRequest;
import com.sina.banking.DTOs.UserDTOs.UpdateUserRequest;
import com.sina.banking.DTOs.UserDTOs.UserResponse;
import com.sina.banking.models.User;
import com.sina.banking.models.UserRole;
import com.sina.banking.models.UserStatus;
import com.sina.banking.security.AppUserPrincipal;
import com.sina.banking.services.UserService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({UserController.class, UserControllerTest.MethodSecurityTestConfig.class})
class UserControllerTest {

    // Method security only (@PreAuthorize) - URL-level rules are permitAll here, since we're
    // injecting the Authentication directly per-request rather than going through the real JWT
    // filter chain (that's SecurityConfig's job, not this test's).
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
    private final Integer SOMEONE_ELSE_ID = 99;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

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
    void createUser_happyPath_return201() throws Exception {
        CreateUserRequest request = new CreateUserRequest("test", "user", "testuser", "pass", "test@bank.local");
        UserResponse response = new UserResponse(1, "test", "user", "testuser", "test@bank.local", UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());

        when(userService.createUser(request)).thenReturn(response);

        mockMvc.perform(post("/api/users")
                .with(authentication(authAs(USER_ID, UserRole.USER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void getUserById_ownProfile_returns200() throws Exception {
        UserResponse response = new UserResponse(1, "Test", "User", "testuser", "test@example.com",
                UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        when(userService.getUserById(1)).thenReturn(response);

        mockMvc.perform(get("/api/users/1").with(authentication(authAs(1, UserRole.USER))))
                .andExpect(status().isOk());
    }

    @Test
    void getUserById_someoneElseProfile_returns403() throws Exception {
        mockMvc.perform(get("/api/users/2").with(authentication(authAs(1, UserRole.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getUserById_asAdmin_returns200() throws Exception {
        UserResponse response = new UserResponse(2, "Other", "User", "otheruser", "other@example.com",
                UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        when(userService.getUserById(2)).thenReturn(response);

        mockMvc.perform(get("/api/users/2").with(authentication(authAs(USER_ID, UserRole.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void getAllUsers_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/users").with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllUsers_asAdmin_returns200() throws Exception {
        mockMvc.perform(get("/api/users").with(authentication(authAs(USER_ID, UserRole.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void updateUser_ownProfile_return200() throws Exception {
        UpdateUserRequest updateRequest = new UpdateUserRequest("test_user", "test_lastname", "test@bank.local");
        UserResponse response = new UserResponse(USER_ID, updateRequest.firstName(), updateRequest.lastName(), "testuser", updateRequest.email(), UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());

        when(userService.updateUser(USER_ID, updateRequest)).thenReturn(response);

        mockMvc.perform(put("/api/users/" + USER_ID).with(authentication(authAs(USER_ID, UserRole.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk());
    }

    @Test
    void updateUser_someoneElseProfile_return403() throws Exception {
        UpdateUserRequest updateRequest = new UpdateUserRequest("test_user", "test_lastname", "test@bank.local");

        mockMvc.perform(put("/api/users/" + SOMEONE_ELSE_ID).with(authentication(authAs(USER_ID, UserRole.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateUser_asAdmin_return200() throws Exception {
        UpdateUserRequest updateRequest = new UpdateUserRequest("test_user", "test_lastname", "test@bank.local");
        UserResponse response = new UserResponse(USER_ID, updateRequest.firstName(), updateRequest.lastName(), "testuser", updateRequest.email(), UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());

        when(userService.updateUser(SOMEONE_ELSE_ID, updateRequest)).thenReturn(response);

        mockMvc.perform(put("/api/users/" + SOMEONE_ELSE_ID).with(authentication(authAs(USER_ID, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk());
    }

    @Test
    void changePassword_ownProfile_return204() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest("$password$", "$newpassword$");

        mockMvc.perform(post("/api/users/" + USER_ID + "/change-password")
                .with(authentication(authAs(USER_ID, UserRole.USER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    void changePassword_someoneElseProfile_return403() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest("$password$", "$newpassword$");

        mockMvc.perform(post("/api/users/" + SOMEONE_ELSE_ID + "/change-password")
                        .with(authentication(authAs(USER_ID, UserRole.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void changePassword_asAdmin_return204() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest("$password$", "$newpassword$");

        mockMvc.perform(post("/api/users/" + SOMEONE_ELSE_ID + "/change-password")
                        .with(authentication(authAs(USER_ID, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    void disableUser_asNonAdmin_return403() throws Exception {
        mockMvc.perform(post("/api/users/" + USER_ID + "/disable")
                .with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void disableUser_asAdmin_return204() throws Exception {
        mockMvc.perform(post("/api/users/" + USER_ID + "/disable")
                        .with(authentication(authAs(USER_ID, UserRole.ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void enableUser_asNonAdmin_return403() throws Exception {
        mockMvc.perform(post("/api/users/" + USER_ID + "/enable")
                .with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void enableUser_asAdmin_return204() throws Exception {
        mockMvc.perform(post("/api/users/" + USER_ID + "/enable")
                        .with(authentication(authAs(USER_ID, UserRole.ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void promoteToAdmin_asNonAdmin_return403() throws Exception {
        mockMvc.perform(post("/api/users/" + USER_ID + "/promote")
                        .with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void promoteToAdmin_asAdmin_return204() throws Exception {
        mockMvc.perform(post("/api/users/" + USER_ID + "/promote")
                        .with(authentication(authAs(USER_ID, UserRole.ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void demoteToUser_asNonAdmin_return403() throws Exception {
        mockMvc.perform(post("/api/users/" + USER_ID + "/demote")
                        .with(authentication(authAs(USER_ID, UserRole.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void demoteToUser_asAdmin_return204() throws Exception {
        mockMvc.perform(post("/api/users/" + SOMEONE_ELSE_ID + "/demote")
                        .with(authentication(authAs(USER_ID, UserRole.ADMIN))))
                .andExpect(status().isNoContent());
    }
}
