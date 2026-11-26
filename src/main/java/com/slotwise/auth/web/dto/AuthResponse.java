package com.slotwise.auth.web.dto;

import com.slotwise.user.web.dto.UserResponse;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user) {

    @Override
    public String toString() {
        return "AuthResponse[user=" + user.id() + "]";
    }
}
