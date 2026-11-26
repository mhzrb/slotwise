package com.slotwise.availability.web.dto;

import java.util.List;

public record ProviderAvailabilityResponse(
        WeeklyScheduleResponse weeklySchedule,
        List<AvailabilityExceptionResponse> exceptions) {
}
