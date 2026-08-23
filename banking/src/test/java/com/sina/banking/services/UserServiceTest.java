package com.sina.banking.services;

import com.sina.banking.DTOs.UserDtos.*;
import com.sina.banking.models.User;
import com.sina.banking.models.UserStatus;
import com.sina.banking.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    private CreateUserRequest userRequest;

    private final User user = new User("John", "Doe", "testUser", "@#$%123", "testuser@bank.local");


    @BeforeEach
    void setup() {
        userRequest = new CreateUserRequest(
                user.getFirstName(),
                user.getLastName(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getEmail()
        );
    }

    @Test
    void createUser_successfullyCreateUser() {
        when(userRepository.existsByUsername(userRequest.username())).thenReturn(false);
        when(userRepository.existsByEmail(userRequest.email())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.createUser(userRequest);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.username()).isEqualTo(userRequest.username());
    }

    @Test
    void createUser_duplicateUsername() {
        when(userRepository.existsByUsername(userRequest.username())).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(userRequest)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username already exists");
    }

    @Test
    void createUser_duplicateEmail() {
        when(userRepository.existsByUsername(userRequest.username())).thenReturn(false);
        when(userRepository.existsByEmail(userRequest.email())).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(userRequest)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email already exists");
    }

    @Test
    void updateUser_successfullyUpdateUser() {
        UpdateUserRequest updateUserRequest = new UpdateUserRequest("Jane", "Smith", "updateduser@bank.local");

        when(userRepository.findById(1)).thenReturn(Optional.of(user));
        UserResponse response = userService.updateUser(1, updateUserRequest);

        assertThat(response).isNotNull();
        assertThat(response.username()).isEqualTo(user.getUsername());
        assertThat(response.firstName()).isEqualTo(updateUserRequest.firstName());
        assertThat(response.lastName()).isEqualTo(updateUserRequest.lastName());
        assertThat(response.email()).isEqualTo(updateUserRequest.email());
    }

    @Test
    void updateUser_userNotFound() {
        UpdateUserRequest updateUserRequest = new UpdateUserRequest("Jane", "Smith", "updateduser@bank.local");

        when(userRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(1, updateUserRequest)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void changePassword_successfullyChangePassword() {
        String newPassword = "!@#new_password";
        ChangePasswordRequest passwordRequest = new ChangePasswordRequest(user.getPasswordHash(), newPassword);

        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        userService.changePassword(1, passwordRequest);

        assertThat(user.getPasswordHash()).isEqualTo(newPassword);

    }

    @Test
    void changePassword_userNotFound() {
        String newPassword = "!@#new_password";
        ChangePasswordRequest passwordRequest = new ChangePasswordRequest(userRequest.password(), newPassword);
        when(userRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changePassword(1, passwordRequest)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void changePassword_wrongCurrentPassword() {
        String newPassword = "!@#new_password";
        String wrongPassword = "$wrongPassword$";
        ChangePasswordRequest passwordRequest = new ChangePasswordRequest(wrongPassword, newPassword);

        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.changePassword(1, passwordRequest)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current password is incorrect");
    }

    @Test
    void disableUser_successfullyDisableUser() {
        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        userService.disableUser(1);

        assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);
    }

    @Test
    void disableUser_userNotFound() {
        when(userRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.disableUser(1)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void enableUser_successfullyEnableUser() {
        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        userService.enableUser(1);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void enableUser_userNotFound() {
        when(userRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.enableUser(1)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void getUserById_successfullyGetUser() {
        when(userRepository.findById(1)).thenReturn(Optional.of(user));
        UserResponse response = userService.getUserById(1);
        assertThat(response).isNotNull();
        assertThat(response.username()).isEqualTo(user.getUsername());
        assertThat(response.firstName()).isEqualTo(user.getFirstName());
        assertThat(response.lastName()).isEqualTo(user.getLastName());
        assertThat(response.email()).isEqualTo(user.getEmail());
        assertThat(response.status()).isEqualTo(user.getStatus());
    }

    @Test
    void getUserById_userNotFound() {
        when(userRepository.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(1)).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("User not found");
    }

    @SuppressWarnings("null")
    @Test
    void getAllUsers_getListOfUsers() {
        User anotherUser = new User("Jane", "Smith", "testuser", "password", "janesmith@bank.local");
        List<User> users = List.of(user, anotherUser);

        when(userRepository.findAll()).thenReturn(users);

        List<UserResponse> responseList = userService.getAllUsers();
        assertThat(responseList.size()).isEqualTo(2);
        assertThat(responseList).extracting(UserResponse::username)
                .containsExactlyInAnyOrder(user.getUsername(), anotherUser.getUsername());
    }

    @Test
    void getUserByEmail_successfullyGetUser() {
        when(userRepository.findByEmail(userRequest.email())).thenReturn(Optional.of(user));
        UserResponse response = userService.getUserByEmail(userRequest.email());
        assertThat(response).isNotNull();
        assertThat(response.username()).isEqualTo(user.getUsername());
        assertThat(response.firstName()).isEqualTo(user.getFirstName());
        assertThat(response.lastName()).isEqualTo(user.getLastName());
        assertThat(response.email()).isEqualTo(user.getEmail());
        assertThat(response.status()).isEqualTo(user.getStatus());
    }

    @Test
    void getUserByEmail_userNotFound() {
        when(userRepository.findByEmail(userRequest.email())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserByEmail(userRequest.email())).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void getUserByUsername_successfullyGetUser() {
        when(userRepository.findByUsername(userRequest.username())).thenReturn(Optional.of(user));
        UserResponse response = userService.getUserByUsername(userRequest.username());
        assertThat(response).isNotNull();
        assertThat(response.username()).isEqualTo(user.getUsername());
        assertThat(response.firstName()).isEqualTo(user.getFirstName());
        assertThat(response.lastName()).isEqualTo(user.getLastName());
        assertThat(response.email()).isEqualTo(user.getEmail());
        assertThat(response.status()).isEqualTo(user.getStatus());
    }

    @Test
    void getUserByUsername_userNotFound() {
        when(userRepository.findByUsername(userRequest.username())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserByUsername(userRequest.username())).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("User not found");
    }
}
