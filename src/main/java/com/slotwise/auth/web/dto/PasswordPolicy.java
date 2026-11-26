package com.slotwise.auth.web.dto;

/**
 * Password rules. The 72-byte max reflects BCrypt's input limit: longer
 * passwords would be silently truncated.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;
    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d).+$";
    public static final String MESSAGE = "must contain at least one letter and one digit";

    private PasswordPolicy() {
    }
}
