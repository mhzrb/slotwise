package com.slotwise.auth.web.dto;

import jakarta.validation.constraints.Size;

/** The refresh token is optional; without it only the access token is revoked. */
public record LogoutRequest(@Size(max = 128) String refreshToken) {

    @Override
    public String toString() {
        return "LogoutRequest[refreshToken=***]";
    }
}
