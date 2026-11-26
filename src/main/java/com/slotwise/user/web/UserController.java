package com.slotwise.user.web;

import com.slotwise.auth.security.AuthenticatedUser;
import com.slotwise.user.service.UserService;
import com.slotwise.user.web.dto.ChangePasswordRequest;
import com.slotwise.user.web.dto.UpdateProfileRequest;
import com.slotwise.user.web.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Users", description = "Profile of the authenticated user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "Get the current user's profile")
    public UserResponse me(AuthenticatedUser currentUser) {
        return UserResponse.from(userService.getById(currentUser.id()));
    }

    @PutMapping
    @Operation(summary = "Update the current user's profile")
    public UserResponse update(AuthenticatedUser currentUser, @Valid @RequestBody UpdateProfileRequest request) {
        return UserResponse.from(userService.updateProfile(currentUser.id(), request.fullName(), request.phone()));
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change password", description = "Revokes all refresh tokens of the user.")
    public void changePassword(AuthenticatedUser currentUser, @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(currentUser.id(), request.currentPassword(), request.newPassword());
    }
}
