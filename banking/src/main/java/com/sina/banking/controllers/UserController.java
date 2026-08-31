package com.sina.banking.controllers;

import com.sina.banking.DTOs.UserDTOs.CreateUserRequest;
import com.sina.banking.DTOs.UserDTOs.UpdateUserRequest;
import com.sina.banking.DTOs.UserDTOs.ChangePasswordRequest;
import com.sina.banking.DTOs.UserDTOs.UserResponse;
import com.sina.banking.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@Tag(name = "Users")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Register a new user")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created"),
            @ApiResponse(responseCode = "400", description = "Username or email already exists")
    })
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody CreateUserRequest request) {
        // Never log request.password() here or anywhere downstream - only non-secret fields.
        log.debug("POST /api/users username={}", request.username());
        UserResponse userResponse = userService.createUser(request);
        return ResponseEntity.created(URI.create("/api/users/" + userResponse.id())).body(userResponse);
    }

    @Operation(summary = "Get a user by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User found"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    @PreAuthorize("#id == authentication.principal.id or hasRole('ADMIN')")
    @GetMapping("/{id}")
    public UserResponse getUserById(@PathVariable Integer id) {
        log.debug("GET /api/users/{}", id);
        return userService.getUserById(id);
    }

    @Operation(summary = "List all users")
    @ApiResponse(responseCode = "200", description = "List of users (possibly empty)")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public List<UserResponse> getAllUsers() {
        log.debug("GET /api/users");
        return userService.getAllUsers();
    }

    @Operation(summary = "Update a user's profile (first name, last name, email)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile updated"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    @PreAuthorize("#id == authentication.principal.id or hasRole('ADMIN')")
    @PutMapping("/{id}")
    public UserResponse updateUser(@PathVariable Integer id, @RequestBody UpdateUserRequest request) {
        log.debug("PUT /api/users/{}", id);
        return userService.updateUser(id, request);
    }

    @Operation(summary = "Change a user's password, given their current password")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password changed"),
            @ApiResponse(responseCode = "400", description = "Current password is incorrect"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    @PostMapping("/{id}/change-password")
    @PreAuthorize("#id == authentication.principal.id or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@PathVariable Integer id, @RequestBody ChangePasswordRequest request) {
        // Never log request.currentPassword()/newPassword() - only the id.
        log.debug("POST /api/users/{}/change-password", id);
        userService.changePassword(id, request);
    }

    @Operation(summary = "Disable a user, preventing them from signing in")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User disabled"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableUser(@PathVariable Integer id) {
        log.debug("POST /api/users/{}/disable", id);
        userService.disableUser(id);
    }

    @Operation(summary = "Re-enable a previously disabled user")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User enabled"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enableUser(@PathVariable Integer id) {
        log.debug("POST /api/users/{}/enable", id);
        userService.enableUser(id);
    }
}
