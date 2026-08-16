package com.sina.banking.services;

import com.sina.banking.DTOs.UserDtos.UpdateUserRequest;
import com.sina.banking.DTOs.UserDtos.ChangePasswordRequest;
import com.sina.banking.DTOs.UserDtos.UserResponse;
import com.sina.banking.DTOs.UserDtos.CreateUserRequest;
import com.sina.banking.models.User;
import com.sina.banking.repositories.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class UserService {

    private final UserRepository userRepository;


    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Username already exists: " + request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email already exists: " + request.email());
        }

        //TODO: Hash password
        String hash = request.password();
        User user = new User(request.firstName(), request.lastName(), request.username(), hash, request.email());

        User save = userRepository.save(user);
        return UserResponse.from(save);
    }

    @Transactional
    public UserResponse updateUser(Integer id, UpdateUserRequest request) {
        User user = findUserByIdOrThrow(id);

        user.updateUserProfile(request.firstName(), request.lastName(), request.email());
        return UserResponse.from(user);
    }

    @Transactional
    public void changePassword(Integer id, ChangePasswordRequest request) {
        User user = findUserByIdOrThrow(id);

        //TODO: hash the current and the new password
        String currentPasswordHashed = request.currentPassword();
        String newPasswordHashed = request.newPassword();

        if(!user.getPasswordHash().equals(currentPasswordHashed)) {
            throw new IllegalArgumentException("current password is incorrect");
        }

        user.changePassword(newPasswordHashed);
    }

    @Transactional
    public void disableUser(Integer id) {
        User user = findUserByIdOrThrow(id);
        user.disable();
    }

    @Transactional
    public void enableUser(Integer id) {
        User user = findUserByIdOrThrow(id);
        user.enable();
    }

    public UserResponse getUserById(Integer id) {
        User user = findUserByIdOrThrow(id);
        return UserResponse.from(user);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream().map(UserResponse::from).toList();
    }

    public UserResponse getUserByEmail(String email) {
        User user = findUserByEmailOrThrow(email);
        return UserResponse.from(user);
    }

    public UserResponse getUserByUsername(String username) {
        User user = findUserByUsernameOrThrow(username);
        return UserResponse.from(user);
    }

    private User findUserByIdOrThrow(Integer id) {
        return userRepository.findById(id).orElseThrow( () -> new NoSuchElementException("User not found: " + id));
    }

    private User findUserByEmailOrThrow(String email) {
        return userRepository.findByEmail(email).orElseThrow( () -> new NoSuchElementException("User not found: " + email));
    }

    private User findUserByUsernameOrThrow(String username) {
        return userRepository.findByUsername(username).orElseThrow( () -> new NoSuchElementException("User not found: " + username));
    }

}
