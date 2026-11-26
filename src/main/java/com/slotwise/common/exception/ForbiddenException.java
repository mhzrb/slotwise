package com.slotwise.common.exception;

import org.springframework.http.HttpStatus;

/** Authenticated, but not allowed to act on this particular resource (HTTP 403). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }
}
