package com.slotwise.user.web;

import com.slotwise.auth.security.AuthenticatedUser;
import com.slotwise.common.web.PageResponse;
import com.slotwise.user.domain.Role;
import com.slotwise.user.service.UserService;
import com.slotwise.user.web.dto.UpdateUserStatusRequest;
import com.slotwise.user.web.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Users", description = "User administration (ADMIN only)")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "Search users", description = "Filter by role, enabled flag and free text (email / name).")
    public PageResponse<UserResponse> search(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(userService.search(role, enabled, q, pageable),
                UserResponse::from);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get a user")
    public UserResponse get(@PathVariable UUID userId) {
        return UserResponse.from(userService.getById(userId));
    }

    @PatchMapping("/{userId}/status")
    @Operation(summary = "Enable or disable a user", description = "Disabling revokes all refresh tokens.")
    public UserResponse setStatus(AuthenticatedUser admin, @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return UserResponse.from(userService.setEnabled(admin.id(), userId, request.enabled()));
    }
}
