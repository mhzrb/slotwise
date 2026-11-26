package com.slotwise.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint for {@link TimeRange}: when both times are present,
 * start must be strictly before end. Missing times are left to other
 * constraints (some ranges are optional as a whole).
 */
@Documented
@Constraint(validatedBy = TimeRangeValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTimeRange {

    String message() default "startTime must be before endTime";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
