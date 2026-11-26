package com.slotwise.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A syntactically valid request that violates a business rule
 * (e.g. booking in the past, cancelling after the cutoff) - HTTP 422.
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
