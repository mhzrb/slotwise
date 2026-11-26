-- =====================================================================
-- V4: Appointments
--
-- Concurrency safeguard: the exclusion constraint below makes it impossible
-- for two ACTIVE (PENDING/CONFIRMED) appointments of the same provider to
-- have overlapping occupied ranges [start_at, blocked_until), regardless of
-- application code, isolation level or race conditions.
-- blocked_until = end_at + service buffer time.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE appointments (
    id                  UUID           PRIMARY KEY,
    provider_id         UUID           NOT NULL REFERENCES provider_profiles (id),
    customer_id         UUID           NOT NULL REFERENCES users (id),
    service_id          UUID           NOT NULL REFERENCES service_offerings (id),
    start_at            TIMESTAMPTZ    NOT NULL,
    end_at              TIMESTAMPTZ    NOT NULL,
    blocked_until       TIMESTAMPTZ    NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    price               NUMERIC(10, 2) NOT NULL,
    customer_notes      VARCHAR(1000),
    cancellation_reason VARCHAR(500),
    cancelled_by        VARCHAR(20),
    reschedule_count    INTEGER        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_appointments_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED', 'REJECTED')),
    CONSTRAINT ck_appointments_cancelled_by CHECK (cancelled_by IS NULL OR cancelled_by IN ('ADMIN', 'PROVIDER', 'CUSTOMER')),
    CONSTRAINT ck_appointments_time_order CHECK (start_at < end_at AND end_at <= blocked_until),
    CONSTRAINT ck_appointments_price CHECK (price >= 0),
    CONSTRAINT ck_appointments_reschedule_count CHECK (reschedule_count >= 0),
    CONSTRAINT ex_appointments_no_overlap EXCLUDE USING gist (
        provider_id WITH =,
        tstzrange(start_at, blocked_until, '[)') WITH &&
    ) WHERE (status IN ('PENDING', 'CONFIRMED'))
);

CREATE INDEX idx_appointments_provider_start ON appointments (provider_id, start_at);
CREATE INDEX idx_appointments_customer_start ON appointments (customer_id, start_at);
CREATE INDEX idx_appointments_service ON appointments (service_id);
CREATE INDEX idx_appointments_status ON appointments (status);
