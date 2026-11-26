-- =====================================================================
-- V2: Provider profiles and the services they offer
-- =====================================================================

CREATE TABLE provider_profiles (
    id                    UUID          PRIMARY KEY,
    user_id               UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    business_name         VARCHAR(120)  NOT NULL,
    description           VARCHAR(2000),
    -- IANA zone id, e.g. 'America/Toronto'. Working hours are interpreted in it.
    time_zone             VARCHAR(64)   NOT NULL,
    slot_interval_minutes INTEGER       NOT NULL DEFAULT 15,
    active                BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_provider_profiles_user UNIQUE (user_id),
    CONSTRAINT ck_provider_profiles_slot_interval CHECK (slot_interval_minutes BETWEEN 5 AND 240)
);

CREATE INDEX idx_provider_profiles_business_name ON provider_profiles (lower(business_name));

CREATE TABLE service_offerings (
    id               UUID           PRIMARY KEY,
    provider_id      UUID           NOT NULL REFERENCES provider_profiles (id) ON DELETE CASCADE,
    name             VARCHAR(120)   NOT NULL,
    description      VARCHAR(2000),
    duration_minutes INTEGER        NOT NULL,
    buffer_minutes   INTEGER        NOT NULL DEFAULT 0,
    price            NUMERIC(10, 2) NOT NULL,
    active           BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_service_offerings_duration CHECK (duration_minutes BETWEEN 5 AND 720),
    CONSTRAINT ck_service_offerings_buffer CHECK (buffer_minutes BETWEEN 0 AND 240),
    CONSTRAINT ck_service_offerings_price CHECK (price >= 0)
);

-- A provider cannot have two services with the same name (case-insensitive).
-- Also serves as the index for "list services of provider".
CREATE UNIQUE INDEX uk_service_offerings_provider_name ON service_offerings (provider_id, lower(name));
CREATE INDEX idx_service_offerings_active_name ON service_offerings (active, lower(name));
