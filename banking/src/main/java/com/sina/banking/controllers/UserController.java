package com.sina.banking.controllers;

import com.sina.banking.DTOs.UserDtos.CreateUserRequest;
import com.sina.banking.DTOs.UserDtos.UpdateUserRequest;
import com.sina.banking.DTOs.UserDtos.ChangePasswordRequest;
import com.sina.banking.DTOs.UserDtos.UserResponse;
import com.sina.banking.services.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody CreateUserRequest request) {
        // Never log request.password() here or anywhere downstream - only non-secret fields.
        log.debug("POST /api/users username={}", request.username());
        UserResponse userResponse = userService.createUser(request);
        return ResponseEntity.created(URI.create("/api/users/" + userResponse.id())).body(userResponse);
    }

    @GetMapping("/{id}")
    public UserResponse getUserById(@PathVariable Integer id) {
        log.debug("GET /api/users/{}", id);
        return userService.getUserById(id);
    }

    @GetMapping
    public List<UserResponse> getAllUsers() {
        log.debug("GET /api/users");
        return userService.getAllUsers();
    }

    @PutMapping("/{id}")
    public UserResponse updateUser(@PathVariable Integer id, @RequestBody UpdateUserRequest request) {
        log.debug("PUT /api/users/{}", id);
        return userService.updateUser(id, request);
    }

    @PostMapping("/{id}/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@PathVariable Integer id, @RequestBody ChangePasswordRequest request) {
        // Never log request.currentPassword()/newPassword() - only the id.
        log.debug("POST /api/users/{}/change-password", id);
        userService.changePassword(id, request);
    }

    @PostMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableUser(@PathVariable Integer id) {
        log.debug("POST /api/users/{}/disable", id);
        userService.disableUser(id);
    }

    @PostMapping("/{id}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enableUser(@PathVariable Integer id) {
        log.debug("POST /api/users/{}/enable", id);
        userService.enableUser(id);
    }
}
