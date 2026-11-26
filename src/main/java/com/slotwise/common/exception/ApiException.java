package com.slotwise.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for expected business errors. Each carries an HTTP status and a
 * stable, machine-readable error code that clients can rely on.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
