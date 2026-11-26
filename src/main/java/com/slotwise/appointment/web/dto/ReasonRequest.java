package com.slotwise.appointment.web.dto;

import jakarta.validation.constraints.Size;

/** Optional free-text reason for cancellations and rejections. */
public record ReasonRequest(@Size(max = 500) String reason) {
}
