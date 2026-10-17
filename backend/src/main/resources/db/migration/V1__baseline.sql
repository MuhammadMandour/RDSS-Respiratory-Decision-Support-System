-- RDSS baseline schema: cross-cutting tables used by every feature.

CREATE TABLE system_setting (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value TEXT         NOT NULL,
    description   VARCHAR(500),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    VARCHAR(100)
);

-- Append-only audit trail. Stores identifiers only, never clinical payloads or credentials.
CREATE TABLE audit_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_user_id BIGINT,
    actor_username VARCHAR(100),
    actor_role    VARCHAR(30),
    action        VARCHAR(50)  NOT NULL,
    resource_type VARCHAR(50)  NOT NULL,
    resource_id   VARCHAR(100),
    patient_id    BIGINT,
    outcome       VARCHAR(20)  NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    detail        VARCHAR(500),
    client_ip     VARCHAR(64)
);

CREATE INDEX idx_audit_log_occurred_at ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_log_actor ON audit_log (actor_user_id, occurred_at DESC);
CREATE INDEX idx_audit_log_patient ON audit_log (patient_id, occurred_at DESC);
CREATE INDEX idx_audit_log_resource ON audit_log (resource_type, resource_id);

INSERT INTO system_setting (setting_key, setting_value, description) VALUES
    ('clinic.name', 'RDSS Respiratory Clinic', 'Display name used on invoices and reports'),
    ('appointments.slot_minutes', '30', 'Default appointment slot length in minutes'),
    ('appointments.patient_cancel_cutoff_hours', '24', 'Patients may not cancel within this many hours of the start time'),
    ('billing.currency', 'USD', 'ISO 4217 currency code for invoices'),
    ('billing.default_tax_rate', '0.00', 'Default tax rate applied to taxable invoice items (fraction, e.g. 0.05)');
