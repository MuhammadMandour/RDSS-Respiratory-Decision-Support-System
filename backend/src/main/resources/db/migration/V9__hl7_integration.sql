-- HL7 v2 integration: external identifier mapping, message log (inbound/outbound), and the
-- system account used as the author of records created from interfaces.

-- External identifiers (e.g. an EMR's MRN) mapped to RDSS patients. The RDSS MRN itself is
-- matched directly via patient.mrn under the assigning authority "RDSS".
CREATE TABLE patient_identifier (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id BIGINT      NOT NULL REFERENCES patient (id),
    system     VARCHAR(64) NOT NULL,
    value      VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_patient_identifier UNIQUE (system, value)
);
CREATE INDEX idx_patient_identifier_patient ON patient_identifier (patient_id);

CREATE TABLE hl7_message (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    direction             VARCHAR(10)  NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    transport             VARCHAR(10)  NOT NULL CHECK (transport IN ('HTTP', 'MLLP')),
    control_id            VARCHAR(64),
    sending_application   VARCHAR(64),
    sending_facility      VARCHAR(64),
    receiving_application VARCHAR(64),
    receiving_facility    VARCHAR(64),
    message_type          VARCHAR(20),
    hl7_version           VARCHAR(10),
    -- Full message text is retained for audit and reprocessing; access is restricted to administrators.
    raw_message           TEXT         NOT NULL,
    status                VARCHAR(20)  NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'REJECTED', 'PENDING', 'SENT')),
    ack_code              VARCHAR(2)   CHECK (ack_code IS NULL OR ack_code IN ('AA', 'AE', 'AR', 'CA', 'CE', 'CR')),
    ack_message           TEXT,
    error_message         VARCHAR(1000),
    retryable             BOOLEAN      NOT NULL DEFAULT FALSE,
    attempts              INT          NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ,
    duplicate_count       INT          NOT NULL DEFAULT 0,
    patient_id            BIGINT       REFERENCES patient (id),
    resource_type         VARCHAR(50),
    resource_id           VARCHAR(64),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at          TIMESTAMPTZ
);
-- Idempotency: one inbound message per sender and control id.
CREATE UNIQUE INDEX uq_hl7_inbound_control ON hl7_message (sending_facility, sending_application, control_id)
    WHERE direction = 'INBOUND' AND control_id IS NOT NULL;
CREATE INDEX idx_hl7_status_retry ON hl7_message (status, next_attempt_at);
CREATE INDEX idx_hl7_created ON hl7_message (created_at DESC);

-- Scheduling interfaces (SIU) reference appointments by the external placer id.
ALTER TABLE appointment ADD COLUMN external_id VARCHAR(64);
CREATE UNIQUE INDEX uq_appointment_external_id ON appointment (external_id) WHERE external_id IS NOT NULL;

-- Imaging orders from ORM are matched by placer order number.
CREATE UNIQUE INDEX uq_imaging_order_external ON imaging_order (external_order_id) WHERE external_order_id IS NOT NULL;

-- Author of records created by interfaces. Disabled and with an unusable password: it can never log in.
INSERT INTO app_user (username, email, password_hash, full_name, role_code, enabled)
VALUES ('system.hl7', 'system.hl7@rdss.local', '!no-login!', 'HL7 Interface (system)', 'CLINICIAN', FALSE);

INSERT INTO system_setting (setting_key, setting_value, description) VALUES
    ('hl7.rdss_assigning_authority', 'RDSS', 'PID-3 assigning authority that denotes an RDSS MRN'),
    ('hl7.retry.max_attempts', '5', 'Maximum processing attempts for transient HL7 failures'),
    ('hl7.outbound.enabled', 'false', 'Send ADT^A08 updates to the configured outbound endpoint'),
    ('hl7.outbound.host', '', 'Outbound MLLP host (empty = not configured)'),
    ('hl7.outbound.port', '2575', 'Outbound MLLP port');
