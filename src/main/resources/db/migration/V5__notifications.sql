-- =====================================================================
-- V5: Notification outbox
-- Rows are inserted in the same transaction as the business change that
-- caused them (transactional outbox) and delivered asynchronously by a
-- scheduled dispatcher. Delivery failures never affect the appointment.
-- =====================================================================

CREATE TABLE notifications (
    id                UUID         PRIMARY KEY,
    recipient_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    appointment_id    UUID         REFERENCES appointments (id) ON DELETE SET NULL,
    type              VARCHAR(40)  NOT NULL,
    channel           VARCHAR(20)  NOT NULL,
    recipient_address VARCHAR(254) NOT NULL,
    subject           VARCHAR(200) NOT NULL,
    body              TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    last_error        VARCHAR(500),
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    sent_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_notifications_type CHECK (type IN (
        'APPOINTMENT_CREATED', 'APPOINTMENT_REQUEST_RECEIVED', 'APPOINTMENT_CONFIRMED',
        'APPOINTMENT_REJECTED', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_RESCHEDULED',
        'APPOINTMENT_COMPLETED')),
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notifications_attempts CHECK (attempts >= 0)
);

-- Dispatcher polls only pending rows that are due.
CREATE INDEX idx_notifications_pending ON notifications (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, created_at DESC);
