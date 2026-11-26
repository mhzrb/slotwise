package com.slotwise.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The value must be a region-based IANA time-zone id such as {@code Europe/Berlin}. */
@Documented
@Constraint(validatedBy = TimeZoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTimeZone {

    String message() default "must be a valid IANA time zone, e.g. 'America/Toronto'";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
