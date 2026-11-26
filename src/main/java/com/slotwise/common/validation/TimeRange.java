package com.slotwise.common.validation;

import java.time.LocalTime;

/** Implemented by request DTOs that carry a local start/end time pair. */
public interface TimeRange {

    LocalTime startTime();

    LocalTime endTime();
}
