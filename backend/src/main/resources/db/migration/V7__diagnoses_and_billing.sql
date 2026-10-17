-- ICD-10 coding, diagnoses, service catalogue, invoices and payments.

-- ---------------------------------------------------------------------------
-- ICD-10-CM code catalogue. Codes are stored in dotted form (J44.1). The table is
-- seeded with a small CURATED SUBSET relevant to respiratory care; load the full,
-- official release with the administrator importer (CDC/NCHS icd10cm order file).
-- ---------------------------------------------------------------------------
CREATE TABLE icd10_code (
    code         VARCHAR(8)   PRIMARY KEY CHECK (code ~ '^[A-Z][0-9][0-9A-Z](\.[0-9A-Z]{1,4})?$'),
    description  VARCHAR(400) NOT NULL,
    billable     BOOLEAN      NOT NULL,
    code_system  VARCHAR(20)  NOT NULL DEFAULT 'ICD-10-CM',
    code_version VARCHAR(20)  NOT NULL,
    source       VARCHAR(20)  NOT NULL CHECK (source IN ('CURATED_SUBSET', 'OFFICIAL_IMPORT')),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_icd10_description ON icd10_code (lower(description));

CREATE TABLE diagnosis (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id     BIGINT       NOT NULL REFERENCES patient (id),
    encounter_id   BIGINT       REFERENCES clinical_encounter (id),
    -- The code and its description/version are snapshotted so later catalogue changes never
    -- alter what was recorded. Internal ids and diagnosis codes are kept separate.
    icd10_code     VARCHAR(8)   NOT NULL REFERENCES icd10_code (code),
    code_version   VARCHAR(20)  NOT NULL,
    description    VARCHAR(400) NOT NULL,
    diagnosis_type VARCHAR(20)  NOT NULL CHECK (diagnosis_type IN ('PRIMARY', 'SECONDARY', 'COMORBIDITY')),
    status         VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'RESOLVED', 'ENTERED_IN_ERROR')),
    onset_date     DATE,
    resolved_date  DATE,
    notes          VARCHAR(2000),
    error_reason   VARCHAR(500),
    recorded_by    BIGINT       NOT NULL REFERENCES app_user (id),
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_diagnosis_error CHECK (status <> 'ENTERED_IN_ERROR' OR error_reason IS NOT NULL)
);
CREATE INDEX idx_diagnosis_patient ON diagnosis (patient_id, recorded_at DESC);
CREATE INDEX idx_diagnosis_code ON diagnosis (icd10_code);

-- ---------------------------------------------------------------------------
-- Billing
-- ---------------------------------------------------------------------------
CREATE TABLE billing_service (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(30)   NOT NULL UNIQUE,
    name        VARCHAR(200)  NOT NULL,
    category    VARCHAR(30)   NOT NULL CHECK (category IN ('CONSULTATION', 'DIAGNOSTIC_TEST', 'IMAGING', 'PROCEDURE', 'THERAPY', 'OTHER')),
    unit_price  NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    taxable     BOOLEAN       NOT NULL DEFAULT FALSE,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE SEQUENCE invoice_number_seq START WITH 1;

CREATE TABLE invoice (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_number VARCHAR(30)    NOT NULL UNIQUE,
    patient_id     BIGINT         NOT NULL REFERENCES patient (id),
    encounter_id   BIGINT         REFERENCES clinical_encounter (id),
    status         VARCHAR(20)    NOT NULL CHECK (status IN ('DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'VOID')),
    currency       CHAR(3)        NOT NULL,
    issue_date     DATE,
    due_date       DATE,
    subtotal       NUMERIC(12, 2) NOT NULL DEFAULT 0,
    discount_total NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tax_total      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total          NUMERIC(12, 2) NOT NULL DEFAULT 0,
    amount_paid    NUMERIC(12, 2) NOT NULL DEFAULT 0,
    notes          VARCHAR(1000),
    void_reason    VARCHAR(500),
    created_by     BIGINT         NOT NULL REFERENCES app_user (id),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    issued_at      TIMESTAMPTZ,
    voided_at      TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT chk_invoice_amounts CHECK (amount_paid >= 0 AND amount_paid <= total AND total >= 0),
    CONSTRAINT chk_invoice_dates CHECK (due_date IS NULL OR issue_date IS NULL OR due_date >= issue_date),
    CONSTRAINT chk_invoice_issued CHECK (status = 'DRAFT' OR status = 'VOID' OR issued_at IS NOT NULL),
    CONSTRAINT chk_invoice_void CHECK (status <> 'VOID' OR void_reason IS NOT NULL)
);
CREATE INDEX idx_invoice_patient ON invoice (patient_id, created_at DESC);
CREATE INDEX idx_invoice_status ON invoice (status);
CREATE INDEX idx_invoice_issue_date ON invoice (issue_date);

CREATE TABLE invoice_item (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id       BIGINT         NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    service_id       BIGINT         REFERENCES billing_service (id),
    description      VARCHAR(255)   NOT NULL,
    quantity         NUMERIC(10, 2) NOT NULL CHECK (quantity > 0),
    unit_price       NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    discount_percent NUMERIC(5, 2)  NOT NULL DEFAULT 0 CHECK (discount_percent BETWEEN 0 AND 100),
    tax_rate         NUMERIC(6, 4)  NOT NULL DEFAULT 0 CHECK (tax_rate >= 0 AND tax_rate < 1),
    line_subtotal    NUMERIC(12, 2) NOT NULL,
    line_discount    NUMERIC(12, 2) NOT NULL,
    line_tax         NUMERIC(12, 2) NOT NULL,
    line_total       NUMERIC(12, 2) NOT NULL
);
CREATE INDEX idx_invoice_item_invoice ON invoice_item (invoice_id);
CREATE INDEX idx_invoice_item_service ON invoice_item (service_id);

-- ICD-10 codes supporting the invoice (claim-style diagnosis pointers).
CREATE TABLE invoice_diagnosis (
    invoice_id  BIGINT     NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    icd10_code  VARCHAR(8) NOT NULL REFERENCES icd10_code (code),
    sequence_no INT        NOT NULL CHECK (sequence_no BETWEEN 1 AND 12),
    -- Keyed by position so reordering never collides; duplicate codes are rejected by the service.
    PRIMARY KEY (invoice_id, sequence_no)
);

CREATE TABLE payment (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id      BIGINT         NOT NULL REFERENCES invoice (id),
    amount          NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    method          VARCHAR(20)    NOT NULL CHECK (method IN ('CASH', 'CARD', 'BANK_TRANSFER', 'INSURANCE', 'SIMULATED')),
    reference       VARCHAR(100),
    -- TRUE for the demonstration payment flow: no money moves and no gateway is contacted.
    simulated       BOOLEAN        NOT NULL DEFAULT FALSE,
    status          VARCHAR(20)    NOT NULL CHECK (status IN ('COMPLETED', 'REVERSED')),
    reversal_reason VARCHAR(500),
    paid_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    recorded_by     BIGINT         NOT NULL REFERENCES app_user (id),
    CONSTRAINT chk_payment_simulated CHECK ((method = 'SIMULATED') = simulated),
    CONSTRAINT chk_payment_reversal CHECK (status <> 'REVERSED' OR reversal_reason IS NOT NULL)
);
CREATE INDEX idx_payment_invoice ON payment (invoice_id);
CREATE INDEX idx_payment_paid_at ON payment (paid_at);

INSERT INTO billing_service (code, name, category, unit_price, taxable) VALUES
    ('CONS-NEW',   'New patient respiratory consultation',       'CONSULTATION',    180.00, FALSE),
    ('CONS-FU',    'Follow-up respiratory consultation',         'CONSULTATION',    110.00, FALSE),
    ('TELE-FU',    'Telehealth follow-up',                       'CONSULTATION',     80.00, FALSE),
    ('SPIRO',      'Spirometry with bronchodilator response',    'DIAGNOSTIC_TEST',  95.00, FALSE),
    ('FULL-PFT',   'Full pulmonary function tests (incl. DLCO)', 'DIAGNOSTIC_TEST', 240.00, FALSE),
    ('FENO',       'Fractional exhaled nitric oxide (FeNO)',     'DIAGNOSTIC_TEST',  45.00, FALSE),
    ('6MWT',       'Six-minute walk test',                       'DIAGNOSTIC_TEST',  70.00, FALSE),
    ('CXR',        'Chest radiograph (PA)',                      'IMAGING',          60.00, FALSE),
    ('CT-CHEST',   'CT chest',                                   'IMAGING',         320.00, FALSE),
    ('NEB',        'Nebuliser treatment',                        'THERAPY',          35.00, FALSE),
    ('INH-DEVICE', 'Spacer / inhaler device',                    'OTHER',            18.50, TRUE);

INSERT INTO system_setting (setting_key, setting_value, description) VALUES
    ('billing.invoice_prefix', 'INV', 'Prefix for invoice numbers, e.g. INV-2026-000001'),
    ('billing.payment_terms_days', '30', 'Days between issue date and due date');
