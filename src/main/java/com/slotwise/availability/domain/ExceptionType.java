package com.slotwise.availability.domain;

public enum ExceptionType {
    /** Whole day closed. */
    HOLIDAY,
    /** Unavailable: whole day when no times are given, otherwise a partial block. */
    BLOCKED,
    /** Temporary additional availability on a specific date. */
    EXTRA_HOURS
}
