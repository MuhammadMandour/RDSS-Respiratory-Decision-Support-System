-- Imaging: orders, study/series metadata (pixel data stays in Orthanc), and clinician findings.

CREATE SEQUENCE imaging_accession_seq START WITH 1000001;

CREATE TABLE imaging_order (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id       BIGINT       NOT NULL REFERENCES patient (id),
    encounter_id     BIGINT       REFERENCES clinical_encounter (id),
    accession_number VARCHAR(16)  NOT NULL UNIQUE,
    modality         VARCHAR(8)   NOT NULL CHECK (modality IN ('CT', 'DX', 'CR', 'MR', 'US', 'PT', 'NM', 'OT')),
    body_part        VARCHAR(64)  NOT NULL DEFAULT 'CHEST',
    procedure_description VARCHAR(255) NOT NULL,
    clinical_indication VARCHAR(1000),
    priority         VARCHAR(10)  NOT NULL DEFAULT 'ROUTINE' CHECK (priority IN ('ROUTINE', 'URGENT', 'STAT')),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('REQUESTED', 'SCHEDULED', 'COMPLETED', 'CANCELLED')),
    -- Identifier assigned by an external system (e.g. placer/filler order number via HL7 ORM).
    external_order_id VARCHAR(64),
    ordered_by       BIGINT       REFERENCES app_user (id),
    ordered_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_imaging_order_patient ON imaging_order (patient_id, ordered_at DESC);

CREATE TABLE imaging_study (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id          BIGINT       NOT NULL REFERENCES patient (id),
    encounter_id        BIGINT       REFERENCES clinical_encounter (id),
    order_id            BIGINT       REFERENCES imaging_order (id),
    -- Stable references into Orthanc / DICOM.
    study_instance_uid  VARCHAR(64)  NOT NULL UNIQUE,
    orthanc_study_id    VARCHAR(64)  NOT NULL UNIQUE,
    accession_number    VARCHAR(64),
    study_date          DATE,
    study_description   VARCHAR(255),
    modalities          VARCHAR(64),
    number_of_series    INT          NOT NULL DEFAULT 0,
    number_of_instances INT          NOT NULL DEFAULT 0,
    -- DICOM PatientID tag as received; compared to the MRN to flag possible mismatches.
    dicom_patient_id    VARCHAR(64),
    patient_id_mismatch BOOLEAN      NOT NULL DEFAULT FALSE,
    source              VARCHAR(20)  NOT NULL CHECK (source IN ('UPLOAD', 'LINKED', 'DEMO', 'HL7')),
    linked_by           BIGINT       REFERENCES app_user (id),
    linked_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_imaging_study_patient ON imaging_study (patient_id, study_date DESC);
CREATE INDEX idx_imaging_study_order ON imaging_study (order_id);

CREATE TABLE imaging_series_metadata (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    study_id            BIGINT       NOT NULL REFERENCES imaging_study (id) ON DELETE CASCADE,
    series_instance_uid VARCHAR(64)  NOT NULL UNIQUE,
    orthanc_series_id   VARCHAR(64)  NOT NULL,
    modality            VARCHAR(16),
    body_part           VARCHAR(64),
    series_description  VARCHAR(255),
    series_number       INT,
    number_of_instances INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_imaging_series_study ON imaging_series_metadata (study_id);

CREATE TABLE imaging_finding (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    study_id        BIGINT       NOT NULL REFERENCES imaging_study (id),
    series_id       BIGINT       REFERENCES imaging_series_metadata (id),
    finding_type    VARCHAR(30)  NOT NULL CHECK (finding_type IN (
                        'PULMONARY_OPACITY', 'CONSOLIDATION', 'PLEURAL_EFFUSION', 'PULMONARY_NODULE', 'PNEUMOTHORAX',
                        'ATELECTASIS', 'INTERSTITIAL_CHANGES', 'EMPHYSEMA', 'BRONCHIECTASIS', 'CARDIOMEGALY', 'OTHER')),
    laterality      VARCHAR(16)  NOT NULL DEFAULT 'NOT_APPLICABLE'
                    CHECK (laterality IN ('LEFT', 'RIGHT', 'BILATERAL', 'NOT_APPLICABLE')),
    location        VARCHAR(255),
    size_mm         NUMERIC(6, 1) CHECK (size_mm IS NULL OR (size_mm > 0 AND size_mm < 1000)),
    description     VARCHAR(4000) NOT NULL,
    -- Provenance of the finding. AI output is only accepted from a documented, separate service.
    source          VARCHAR(20)  NOT NULL CHECK (source IN ('CLINICIAN', 'RADIOLOGIST_REPORT', 'DEMONSTRATION', 'AI_UNVALIDATED')),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PRELIMINARY', 'FINAL', 'ENTERED_IN_ERROR')),
    -- Optional pointer to the image the finding refers to.
    sop_instance_uid VARCHAR(64),
    frame_number    INT,
    error_reason    VARCHAR(500),
    recorded_by     BIGINT       NOT NULL REFERENCES app_user (id),
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_finding_error CHECK (status <> 'ENTERED_IN_ERROR' OR error_reason IS NOT NULL)
);
CREATE INDEX idx_imaging_finding_study ON imaging_finding (study_id);

-- CDSS: surface documented imaging findings as assessment context.
ALTER TABLE cdss_rule DROP CONSTRAINT cdss_rule_category_check;
ALTER TABLE cdss_rule ADD CONSTRAINT cdss_rule_category_check CHECK (category IN
    ('DETERIORATION', 'OXYGENATION', 'OBSERVATIONS', 'COPD', 'ASTHMA', 'RED_FLAG', 'DATA_QUALITY', 'TREND', 'IMAGING'));

INSERT INTO cdss_rule (code, name, category, description, rule_version, validated_method, parameters) VALUES
('IMAGING_FINDINGS', 'Documented imaging findings', 'IMAGING',
 'Lists clinician- or radiologist-documented respiratory imaging findings from the last N days (default 180) as context. Findings from unvalidated AI or demonstration sources are labelled as such. The rule never interprets images itself and makes no diagnostic claim.',
 '1.0.0', FALSE, '{"lookbackDays": 180}');
