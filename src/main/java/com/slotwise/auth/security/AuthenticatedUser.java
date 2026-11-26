package com.slotwise.auth.security;

import com.slotwise.user.domain.Role;

import java.util.UUID;

/**
 * The caller's identity, resolved from the validated JWT. Controllers declare
 * it as a method parameter (see {@link AuthenticatedUserArgumentResolver}).
 */
public record AuthenticatedUser(UUID id, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean isProvider() {
        return role == Role.PROVIDER;
    }

    public boolean isCustomer() {
        return role == Role.CUSTOMER;
    }
}
