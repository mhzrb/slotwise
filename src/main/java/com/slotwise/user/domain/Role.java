package com.slotwise.user.domain;

public enum Role {
    ADMIN,
    PROVIDER,
    CUSTOMER;

    /** Spring Security authority name, e.g. {@code ROLE_PROVIDER}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
