-- Clinician availability and appointments. Double-booking is prevented at the database
-- level with exclusion constraints, in addition to the service-level checks.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE clinician_availability (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    clinician_id  BIGINT      NOT NULL REFERENCES app_user (id),
    -- ISO day of week: 1 = Monday ... 7 = Sunday
    day_of_week   SMALLINT    NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time    TIME        NOT NULL,
    end_time      TIME        NOT NULL,
    slot_minutes  SMALLINT    NOT NULL DEFAULT 30 CHECK (slot_minutes BETWEEN 5 AND 240),
    valid_from    DATE        NOT NULL DEFAULT CURRENT_DATE,
    valid_to      DATE,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_availability_times CHECK (start_time < end_time),
    CONSTRAINT chk_availability_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX idx_availability_clinician ON clinician_availability (clinician_id, day_of_week) WHERE active;

CREATE TABLE appointment (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id       BIGINT       NOT NULL REFERENCES patient (id),
    clinician_id     BIGINT       NOT NULL REFERENCES app_user (id),
    starts_at        TIMESTAMPTZ  NOT NULL,
    ends_at          TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('REQUESTED', 'SCHEDULED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    appointment_type VARCHAR(30)  NOT NULL CHECK (appointment_type IN ('CONSULTATION', 'FOLLOW_UP', 'SPIROMETRY', 'IMAGING_REVIEW', 'TELEHEALTH')),
    reason           VARCHAR(500),
    notes            VARCHAR(1000),
    encounter_id     BIGINT       UNIQUE REFERENCES clinical_encounter (id),
    rescheduled_from_id BIGINT    REFERENCES appointment (id),
    cancelled_at     TIMESTAMPTZ,
    cancelled_by     BIGINT       REFERENCES app_user (id),
    cancellation_reason VARCHAR(500),
    created_by       BIGINT       NOT NULL REFERENCES app_user (id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_appointment_times CHECK (starts_at < ends_at),
    CONSTRAINT chk_appointment_cancel CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL),
    -- No overlapping active appointments for the same clinician ...
    CONSTRAINT ex_appointment_clinician_overlap EXCLUDE USING gist (
        clinician_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('REQUESTED', 'SCHEDULED', 'CHECKED_IN', 'COMPLETED')),
    -- ... or for the same patient.
    CONSTRAINT ex_appointment_patient_overlap EXCLUDE USING gist (
        patient_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('REQUESTED', 'SCHEDULED', 'CHECKED_IN', 'COMPLETED'))
);
CREATE INDEX idx_appointment_clinician_time ON appointment (clinician_id, starts_at);
CREATE INDEX idx_appointment_patient_time ON appointment (patient_id, starts_at);
CREATE INDEX idx_appointment_status ON appointment (status);

INSERT INTO system_setting (setting_key, setting_value, description) VALUES
    ('clinic.timezone', 'UTC', 'IANA time zone used to interpret clinician availability (e.g. Europe/London)'),
    ('appointments.patient_booking_horizon_days', '90', 'How far ahead patients may book appointments');
