-- =====================================================================
-- V3: Weekly schedules, breaks and date-specific exceptions.
-- Times are LOCAL times in the provider's time zone; they are converted to
-- UTC instants only when slots are calculated, so DST is handled per date.
-- =====================================================================

CREATE TABLE weekly_schedule_days (
    id          UUID        PRIMARY KEY,
    provider_id UUID        NOT NULL REFERENCES provider_profiles (id) ON DELETE CASCADE,
    day_of_week INTEGER     NOT NULL, -- ISO-8601: 1 = Monday ... 7 = Sunday
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_weekly_schedule_days_provider_day UNIQUE (provider_id, day_of_week),
    CONSTRAINT ck_weekly_schedule_days_dow CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_weekly_schedule_days_range CHECK (start_time < end_time)
);

CREATE TABLE schedule_breaks (
    id              UUID        PRIMARY KEY,
    schedule_day_id UUID        NOT NULL REFERENCES weekly_schedule_days (id) ON DELETE CASCADE,
    start_time      TIME        NOT NULL,
    end_time        TIME        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_schedule_breaks_range CHECK (start_time < end_time)
);

CREATE INDEX idx_schedule_breaks_day ON schedule_breaks (schedule_day_id);

-- HOLIDAY     : whole day closed (no times)
-- BLOCKED     : unavailable; whole day when times are NULL, otherwise a partial block
-- EXTRA_HOURS : temporary additional availability on that date (times required)
CREATE TABLE availability_exceptions (
    id             UUID         PRIMARY KEY,
    provider_id    UUID         NOT NULL REFERENCES provider_profiles (id) ON DELETE CASCADE,
    exception_date DATE         NOT NULL,
    type           VARCHAR(20)  NOT NULL,
    start_time     TIME,
    end_time       TIME,
    reason         VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_availability_exceptions_type CHECK (type IN ('HOLIDAY', 'BLOCKED', 'EXTRA_HOURS')),
    CONSTRAINT ck_availability_exceptions_times CHECK (
        (start_time IS NULL AND end_time IS NULL)
        OR (start_time IS NOT NULL AND end_time IS NOT NULL AND start_time < end_time)),
    CONSTRAINT ck_availability_exceptions_holiday_no_times CHECK (type <> 'HOLIDAY' OR start_time IS NULL),
    CONSTRAINT ck_availability_exceptions_extra_has_times CHECK (type <> 'EXTRA_HOURS' OR start_time IS NOT NULL)
);

CREATE INDEX idx_availability_exceptions_provider_date ON availability_exceptions (provider_id, exception_date);
