-- Respiratory history (versioned), encounters, observations, pulmonary function tests,
-- treatment plans and prescriptions. Clinical rows are never silently overwritten:
-- history is versioned, observations/PFTs are append-only with "entered in error"
-- flags, finalized encounters are immutable and amended through a linked new row.

CREATE TABLE respiratory_history (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id               BIGINT       NOT NULL REFERENCES patient (id),
    version_no               INT          NOT NULL,
    supersedes_id            BIGINT       REFERENCES respiratory_history (id),
    asthma                   BOOLEAN      NOT NULL DEFAULT FALSE,
    copd                     BOOLEAN      NOT NULL DEFAULT FALSE,
    bronchiectasis           BOOLEAN      NOT NULL DEFAULT FALSE,
    interstitial_lung_disease BOOLEAN     NOT NULL DEFAULT FALSE,
    obstructive_sleep_apnea  BOOLEAN      NOT NULL DEFAULT FALSE,
    previous_tuberculosis    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Clinician-documented risk of hypercapnic respiratory failure; drives NEWS2 SpO2 scale 2.
    hypercapnic_respiratory_failure_risk BOOLEAN NOT NULL DEFAULT FALSE,
    target_spo2_low          INT          CHECK (target_spo2_low IS NULL OR target_spo2_low BETWEEN 70 AND 100),
    target_spo2_high         INT          CHECK (target_spo2_high IS NULL OR target_spo2_high BETWEEN 70 AND 100),
    home_oxygen              BOOLEAN      NOT NULL DEFAULT FALSE,
    exacerbations_last_12m   INT          CHECK (exacerbations_last_12m IS NULL OR exacerbations_last_12m BETWEEN 0 AND 100),
    hospitalizations_last_12m INT         CHECK (hospitalizations_last_12m IS NULL OR hospitalizations_last_12m BETWEEN 0 AND 100),
    previous_intubation      BOOLEAN      NOT NULL DEFAULT FALSE,
    inhaler_therapy          TEXT,
    occupational_exposures   TEXT,
    family_history           TEXT,
    vaccinations             TEXT,
    comorbidities            TEXT,
    notes                    TEXT,
    recorded_by              BIGINT       NOT NULL REFERENCES app_user (id),
    recorded_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_respiratory_history_version UNIQUE (patient_id, version_no),
    CONSTRAINT chk_target_spo2_range CHECK (target_spo2_low IS NULL OR target_spo2_high IS NULL OR target_spo2_low < target_spo2_high)
);

CREATE TABLE clinical_encounter (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id             BIGINT       NOT NULL REFERENCES patient (id),
    clinician_id           BIGINT       NOT NULL REFERENCES app_user (id),
    encounter_type         VARCHAR(20)  NOT NULL CHECK (encounter_type IN ('OUTPATIENT', 'INPATIENT', 'EMERGENCY', 'TELEHEALTH')),
    status                 VARCHAR(20)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'FINALIZED', 'SUPERSEDED')),
    started_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finalized_at           TIMESTAMPTZ,
    finalized_by           BIGINT       REFERENCES app_user (id),
    amends_encounter_id    BIGINT       REFERENCES clinical_encounter (id),
    amendment_reason       VARCHAR(500),
    chief_complaint        VARCHAR(500),
    history_of_present_illness TEXT,
    cough                  BOOLEAN,
    cough_duration_days    INT          CHECK (cough_duration_days IS NULL OR cough_duration_days BETWEEN 0 AND 36500),
    sputum                 BOOLEAN,
    sputum_description     VARCHAR(255),
    wheezing               BOOLEAN,
    dyspnea                BOOLEAN,
    -- Modified Medical Research Council dyspnoea scale, 0-4.
    mmrc_grade             INT          CHECK (mmrc_grade IS NULL OR mmrc_grade BETWEEN 0 AND 4),
    -- COPD Assessment Test total score, 0-40.
    cat_score              INT          CHECK (cat_score IS NULL OR cat_score BETWEEN 0 AND 40),
    chest_pain             BOOLEAN,
    fever                  BOOLEAN,
    hemoptysis             BOOLEAN,
    symptom_duration_days  INT          CHECK (symptom_duration_days IS NULL OR symptom_duration_days BETWEEN 0 AND 36500),
    physical_exam          TEXT,
    lab_results            TEXT,
    assessment_notes       TEXT,
    plan_notes             TEXT,
    follow_up              TEXT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_encounter_finalized CHECK (
        (status = 'IN_PROGRESS' AND finalized_at IS NULL) OR
        (status IN ('FINALIZED', 'SUPERSEDED') AND finalized_at IS NOT NULL AND finalized_by IS NOT NULL))
);
CREATE INDEX idx_encounter_patient ON clinical_encounter (patient_id, started_at DESC);
CREATE INDEX idx_encounter_clinician ON clinical_encounter (clinician_id, started_at DESC);
-- An encounter can be amended at most once; further changes amend the amendment.
CREATE UNIQUE INDEX uq_encounter_amends ON clinical_encounter (amends_encounter_id) WHERE amends_encounter_id IS NOT NULL;

CREATE TABLE observation (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id             BIGINT       NOT NULL REFERENCES patient (id),
    encounter_id           BIGINT       REFERENCES clinical_encounter (id),
    observed_at            TIMESTAMPTZ  NOT NULL,
    respiratory_rate       INT          CHECK (respiratory_rate IS NULL OR respiratory_rate BETWEEN 0 AND 100),
    heart_rate             INT          CHECK (heart_rate IS NULL OR heart_rate BETWEEN 0 AND 300),
    systolic_bp            INT          CHECK (systolic_bp IS NULL OR systolic_bp BETWEEN 30 AND 300),
    diastolic_bp           INT          CHECK (diastolic_bp IS NULL OR diastolic_bp BETWEEN 10 AND 200),
    temperature_c          NUMERIC(4, 1) CHECK (temperature_c IS NULL OR temperature_c BETWEEN 25 AND 45),
    spo2                   INT          CHECK (spo2 IS NULL OR spo2 BETWEEN 40 AND 100),
    on_supplemental_oxygen BOOLEAN,
    oxygen_flow_lpm        NUMERIC(4, 1) CHECK (oxygen_flow_lpm IS NULL OR oxygen_flow_lpm BETWEEN 0 AND 80),
    fio2_percent           INT          CHECK (fio2_percent IS NULL OR fio2_percent BETWEEN 21 AND 100),
    consciousness          VARCHAR(15)  CHECK (consciousness IS NULL OR consciousness IN ('ALERT', 'CONFUSION', 'VOICE', 'PAIN', 'UNRESPONSIVE')),
    peak_flow_lpm          INT          CHECK (peak_flow_lpm IS NULL OR peak_flow_lpm BETWEEN 30 AND 1000),
    notes                  VARCHAR(1000),
    entered_in_error       BOOLEAN      NOT NULL DEFAULT FALSE,
    error_reason           VARCHAR(500),
    recorded_by            BIGINT       NOT NULL REFERENCES app_user (id),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_observation_bp CHECK (systolic_bp IS NULL OR diastolic_bp IS NULL OR systolic_bp > diastolic_bp),
    CONSTRAINT chk_observation_error CHECK (NOT entered_in_error OR error_reason IS NOT NULL)
);
CREATE INDEX idx_observation_patient ON observation (patient_id, observed_at DESC);
CREATE INDEX idx_observation_encounter ON observation (encounter_id);

CREATE TABLE pulmonary_function_test (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id               BIGINT       NOT NULL REFERENCES patient (id),
    encounter_id             BIGINT       REFERENCES clinical_encounter (id),
    performed_at             TIMESTAMPTZ  NOT NULL,
    post_bronchodilator      BOOLEAN      NOT NULL DEFAULT FALSE,
    fev1_l                   NUMERIC(4, 2) CHECK (fev1_l IS NULL OR fev1_l BETWEEN 0.1 AND 9),
    fvc_l                    NUMERIC(4, 2) CHECK (fvc_l IS NULL OR fvc_l BETWEEN 0.1 AND 10),
    fev1_percent_predicted   INT          CHECK (fev1_percent_predicted IS NULL OR fev1_percent_predicted BETWEEN 5 AND 200),
    fvc_percent_predicted    INT          CHECK (fvc_percent_predicted IS NULL OR fvc_percent_predicted BETWEEN 5 AND 200),
    dlco_percent_predicted   INT          CHECK (dlco_percent_predicted IS NULL OR dlco_percent_predicted BETWEEN 5 AND 200),
    -- Change in FEV1 after bronchodilator, as percentage of baseline, and absolute in mL.
    bronchodilator_response_percent NUMERIC(5, 1),
    bronchodilator_response_ml INT,
    quality_grade            VARCHAR(2)   CHECK (quality_grade IS NULL OR quality_grade IN ('A', 'B', 'C', 'D', 'E', 'F', 'U')),
    interpretation           TEXT,
    entered_in_error         BOOLEAN      NOT NULL DEFAULT FALSE,
    error_reason             VARCHAR(500),
    recorded_by              BIGINT       NOT NULL REFERENCES app_user (id),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_pft_error CHECK (NOT entered_in_error OR error_reason IS NOT NULL)
);
CREATE INDEX idx_pft_patient ON pulmonary_function_test (patient_id, performed_at DESC);

CREATE TABLE treatment_plan (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id      BIGINT       NOT NULL REFERENCES patient (id),
    encounter_id    BIGINT       REFERENCES clinical_encounter (id),
    title           VARCHAR(200) NOT NULL,
    goals           TEXT,
    interventions   TEXT,
    follow_up_instructions TEXT,
    start_date      DATE         NOT NULL,
    review_date     DATE,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'DISCONTINUED')),
    created_by      BIGINT       NOT NULL REFERENCES app_user (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_plan_dates CHECK (review_date IS NULL OR review_date >= start_date)
);
CREATE INDEX idx_treatment_plan_patient ON treatment_plan (patient_id, start_date DESC);

CREATE TABLE prescription (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id      BIGINT       NOT NULL REFERENCES patient (id),
    encounter_id    BIGINT       REFERENCES clinical_encounter (id),
    medication_name VARCHAR(200) NOT NULL,
    dose            VARCHAR(100) NOT NULL,
    route           VARCHAR(50)  NOT NULL,
    frequency       VARCHAR(100) NOT NULL,
    duration        VARCHAR(100),
    quantity        VARCHAR(50),
    refills         INT          NOT NULL DEFAULT 0 CHECK (refills BETWEEN 0 AND 12),
    instructions    VARCHAR(1000),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'DISCONTINUED')),
    discontinued_reason VARCHAR(500),
    prescribed_by   BIGINT       NOT NULL REFERENCES app_user (id),
    prescribed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_prescription_patient ON prescription (patient_id, prescribed_at DESC);
