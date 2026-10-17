-- Users, roles, departments, clinician profiles and the patient registry.

CREATE TABLE role (
    code         VARCHAR(30) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL
);

INSERT INTO role (code, display_name) VALUES
    ('ADMIN', 'Administrator'),
    ('CLINICIAN', 'Clinical staff'),
    ('PATIENT', 'Patient');

CREATE TABLE app_user (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username       VARCHAR(100) NOT NULL,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    full_name      VARCHAR(200) NOT NULL,
    role_code      VARCHAR(30)  NOT NULL REFERENCES role (code),
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_app_user_username CHECK (username ~ '^[A-Za-z0-9._-]{3,100}$')
);

-- Case-insensitive uniqueness for login identifiers.
CREATE UNIQUE INDEX uq_app_user_username ON app_user (lower(username));
CREATE UNIQUE INDEX uq_app_user_email ON app_user (lower(email));
CREATE INDEX idx_app_user_role ON app_user (role_code);

CREATE TABLE department (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_department_name ON department (lower(name));

CREATE TABLE clinician_profile (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT       NOT NULL UNIQUE REFERENCES app_user (id),
    department_id  BIGINT       REFERENCES department (id),
    title          VARCHAR(50),
    specialty      VARCHAR(150),
    license_number VARCHAR(100),
    phone          VARCHAR(50),
    accepting_appointments BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_clinician_profile_department ON clinician_profile (department_id);

CREATE SEQUENCE patient_mrn_seq START WITH 100001;

CREATE TABLE patient (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mrn                       VARCHAR(20)  NOT NULL UNIQUE,
    user_id                   BIGINT       UNIQUE REFERENCES app_user (id),
    first_name                VARCHAR(100) NOT NULL,
    last_name                 VARCHAR(100) NOT NULL,
    date_of_birth             DATE         NOT NULL,
    sex                       VARCHAR(10)  NOT NULL CHECK (sex IN ('FEMALE', 'MALE', 'OTHER', 'UNKNOWN')),
    phone                     VARCHAR(50),
    email                     VARCHAR(255),
    address_line              VARCHAR(255),
    city                      VARCHAR(100),
    postal_code               VARCHAR(20),
    country                   VARCHAR(100),
    emergency_contact_name    VARCHAR(200),
    emergency_contact_phone   VARCHAR(50),
    emergency_contact_relation VARCHAR(50),
    medical_history           TEXT,
    allergies                 TEXT,
    current_medications       TEXT,
    smoking_status            VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN'
                              CHECK (smoking_status IN ('NEVER', 'FORMER', 'CURRENT', 'UNKNOWN')),
    pack_years                NUMERIC(5, 1) CHECK (pack_years IS NULL OR (pack_years >= 0 AND pack_years <= 300)),
    environmental_exposures   TEXT,
    known_respiratory_conditions TEXT,
    primary_clinician_id      BIGINT       REFERENCES app_user (id),
    active                    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_patient_dob CHECK (date_of_birth > DATE '1890-01-01')
);

CREATE INDEX idx_patient_name ON patient (lower(last_name), lower(first_name));
CREATE INDEX idx_patient_dob ON patient (date_of_birth);
CREATE INDEX idx_patient_primary_clinician ON patient (primary_clinician_id);

-- Clinicians authorized to access a patient's clinical record.
CREATE TABLE patient_care_team (
    patient_id        BIGINT      NOT NULL REFERENCES patient (id) ON DELETE CASCADE,
    clinician_user_id BIGINT      NOT NULL REFERENCES app_user (id),
    reason            VARCHAR(30) NOT NULL CHECK (reason IN ('PRIMARY', 'ASSIGNED', 'REGISTERED', 'APPOINTMENT')),
    added_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (patient_id, clinician_user_id)
);
CREATE INDEX idx_care_team_clinician ON patient_care_team (clinician_user_id);
