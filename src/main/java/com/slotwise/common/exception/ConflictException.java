package com.slotwise.common.exception;

import org.springframework.http.HttpStatus;

/** The request conflicts with the current state of a resource (HTTP 409). */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
