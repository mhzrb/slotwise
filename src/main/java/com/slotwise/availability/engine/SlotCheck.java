package com.slotwise.availability.engine;

/** Outcome of validating one requested start time. */
public enum SlotCheck {
    AVAILABLE,
    /** In the past, or closer than the minimum booking notice. */
    TOO_SOON,
    /** Beyond the booking horizon. */
    TOO_FAR_AHEAD,
    /** Not a slot the provider offers (outside working hours, on a break, misaligned, ...). */
    OUTSIDE_AVAILABILITY,
    /** Overlaps an existing appointment (including its buffer). */
    CONFLICT
}
