package com.slotwise.availability.engine;

import java.time.Instant;

public record Slot(Instant start, Instant end) {
}
