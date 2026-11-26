package com.slotwise.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.ZoneId;

public class TimeZoneValidator implements ConstraintValidator<ValidTimeZone, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // combine with @NotBlank where required
        }
        // Only region ids (with DST rules) are accepted; fixed offsets like
        // "+02:00" would silently ignore daylight saving time.
        return ZoneId.getAvailableZoneIds().contains(value);
    }
}
