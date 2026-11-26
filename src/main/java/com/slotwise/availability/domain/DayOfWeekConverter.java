package com.slotwise.availability.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.DayOfWeek;

/** Stores {@link DayOfWeek} as its ISO-8601 number (1 = Monday ... 7 = Sunday). */
@Converter
public class DayOfWeekConverter implements AttributeConverter<DayOfWeek, Integer> {

    @Override
    public Integer convertToDatabaseColumn(DayOfWeek attribute) {
        return attribute == null ? null : attribute.getValue();
    }

    @Override
    public DayOfWeek convertToEntityAttribute(Integer dbData) {
        return dbData == null ? null : DayOfWeek.of(dbData);
    }
}
