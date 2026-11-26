package com.slotwise.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TimeRangeValidator implements ConstraintValidator<ValidTimeRange, TimeRange> {

    @Override
    public boolean isValid(TimeRange value, ConstraintValidatorContext context) {
        if (value == null || value.startTime() == null || value.endTime() == null) {
            return true;
        }
        return value.startTime().isBefore(value.endTime());
    }
}
