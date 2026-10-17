-- Respiratory clinical decision support: guideline references, rule registry,
-- immutable assessments and the alerts they produced, plus clinician review.

CREATE TABLE guideline_reference (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    title       VARCHAR(500) NOT NULL,
    publisher   VARCHAR(200) NOT NULL,
    version     VARCHAR(100) NOT NULL,
    published_year INT,
    url         VARCHAR(500),
    citation    VARCHAR(1000),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE cdss_rule (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          VARCHAR(50)  NOT NULL UNIQUE,
    name          VARCHAR(200) NOT NULL,
    category      VARCHAR(30)  NOT NULL CHECK (category IN ('DETERIORATION', 'OXYGENATION', 'OBSERVATIONS', 'COPD', 'ASTHMA', 'RED_FLAG', 'DATA_QUALITY', 'TREND')),
    description   VARCHAR(2000) NOT NULL,
    -- Version of the implemented logic; bumped whenever the Java implementation changes behaviour.
    rule_version  VARCHAR(20)  NOT NULL,
    validated_method BOOLEAN   NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Tunable parameters (only where the source guideline allows local configuration).
    parameters    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    VARCHAR(100)
);

CREATE TABLE cdss_rule_guideline (
    rule_id      BIGINT NOT NULL REFERENCES cdss_rule (id) ON DELETE CASCADE,
    guideline_id BIGINT NOT NULL REFERENCES guideline_reference (id),
    PRIMARY KEY (rule_id, guideline_id)
);

CREATE TABLE clinical_assessment (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id      BIGINT       NOT NULL REFERENCES patient (id),
    encounter_id    BIGINT       REFERENCES clinical_encounter (id),
    requested_by    BIGINT       NOT NULL REFERENCES app_user (id),
    assessed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    engine_version  VARCHAR(20)  NOT NULL,
    -- Exact inputs used, rule versions applied and data-quality notes (provenance for audit).
    input_snapshot  JSONB        NOT NULL,
    rules_applied   JSONB        NOT NULL,
    missing_data    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    news2_score     INT          CHECK (news2_score IS NULL OR news2_score BETWEEN 0 AND 20),
    news2_risk      VARCHAR(20)  CHECK (news2_risk IS NULL OR news2_risk IN ('LOW', 'LOW_MEDIUM', 'MEDIUM', 'HIGH')),
    news2_scale     SMALLINT     CHECK (news2_scale IS NULL OR news2_scale IN (1, 2)),
    highest_severity VARCHAR(10) NOT NULL CHECK (highest_severity IN ('NONE', 'INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    -- Optional, separately labelled machine-learning output. Never merged into rule findings.
    inference_output JSONB,
    review_status   VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (review_status IN ('PENDING', 'REVIEWED')),
    reviewed_by     BIGINT       REFERENCES app_user (id),
    reviewed_at     TIMESTAMPTZ,
    review_note     VARCHAR(2000),
    CONSTRAINT chk_assessment_review CHECK (
        (review_status = 'PENDING' AND reviewed_at IS NULL) OR
        (review_status = 'REVIEWED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL))
);
CREATE INDEX idx_assessment_patient ON clinical_assessment (patient_id, assessed_at DESC);
CREATE INDEX idx_assessment_encounter ON clinical_assessment (encounter_id);
CREATE INDEX idx_assessment_review ON clinical_assessment (review_status);

CREATE TABLE cdss_alert (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    assessment_id   BIGINT       NOT NULL REFERENCES clinical_assessment (id) ON DELETE CASCADE,
    rule_code       VARCHAR(50)  NOT NULL,
    rule_version    VARCHAR(20)  NOT NULL,
    category        VARCHAR(30)  NOT NULL,
    severity        VARCHAR(10)  NOT NULL CHECK (severity IN ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    title           VARCHAR(300) NOT NULL,
    explanation     VARCHAR(4000) NOT NULL,
    suggestion      VARCHAR(2000),
    evidence        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    guideline_codes JSONB        NOT NULL DEFAULT '[]'::jsonb,
    disposition     VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (disposition IN ('PENDING', 'ACCEPTED', 'DISMISSED')),
    disposition_reason VARCHAR(1000),
    disposition_by  BIGINT       REFERENCES app_user (id),
    disposition_at  TIMESTAMPTZ,
    CONSTRAINT chk_alert_dismiss_reason CHECK (disposition <> 'DISMISSED' OR disposition_reason IS NOT NULL)
);
CREATE INDEX idx_alert_assessment ON cdss_alert (assessment_id);
CREATE INDEX idx_alert_rule ON cdss_alert (rule_code, severity);

-- ---------------------------------------------------------------------------
-- Guideline sources. Citations refer to the published documents; the rules
-- implement only the specific criteria documented in each rule's description.
-- ---------------------------------------------------------------------------
INSERT INTO guideline_reference (code, title, publisher, version, published_year, url, citation) VALUES
('RCP-NEWS2',
 'National Early Warning Score (NEWS) 2: Standardising the assessment of acute-illness severity in the NHS',
 'Royal College of Physicians', 'NEWS2 (December 2017)', 2017,
 'https://www.rcp.ac.uk/improving-care/resources/national-early-warning-score-news-2/',
 'Royal College of Physicians. National Early Warning Score (NEWS) 2. Updated report of a working party. London: RCP, 2017.'),
('BTS-O2-2017',
 'BTS guideline for oxygen use in adults in healthcare and emergency settings',
 'British Thoracic Society', '2017', 2017,
 'https://www.brit-thoracic.org.uk/quality-improvement/guidelines/emergency-oxygen/',
 'O''Driscoll BR, Howard LS, Earis J, Mak V. BTS guideline for oxygen use in adults in healthcare and emergency settings. Thorax 2017;72(Suppl 1):ii1-ii90.'),
('GOLD-2025',
 'Global Strategy for the Prevention, Diagnosis and Management of Chronic Obstructive Pulmonary Disease',
 'Global Initiative for Chronic Obstructive Lung Disease', '2025 Report', 2024,
 'https://goldcopd.org/',
 'Global Initiative for Chronic Obstructive Lung Disease (GOLD). Global Strategy for Prevention, Diagnosis and Management of COPD: 2025 Report.'),
('GINA-2024',
 'Global Strategy for Asthma Management and Prevention',
 'Global Initiative for Asthma', '2024 update', 2024,
 'https://ginasthma.org/',
 'Global Initiative for Asthma. Global Strategy for Asthma Management and Prevention, 2024. Available from www.ginasthma.org.'),
('NICE-NG12',
 'Suspected cancer: recognition and referral (NG12)',
 'National Institute for Health and Care Excellence', 'NG12 (2015, last updated 2023)', 2015,
 'https://www.nice.org.uk/guidance/ng12',
 'National Institute for Health and Care Excellence. Suspected cancer: recognition and referral. NICE guideline NG12. London: NICE; 2015.'),
('ERS-ATS-2022',
 'ERS/ATS technical standard on interpretive strategies for routine lung function tests',
 'European Respiratory Society / American Thoracic Society', '2022', 2022,
 'https://doi.org/10.1183/13993003.01499-2021',
 'Stanojevic S, Kaminsky DA, Miller MR, et al. ERS/ATS technical standard on interpretive strategies for routine lung function tests. Eur Respir J 2022;60:2101499.');

-- ---------------------------------------------------------------------------
-- Rule registry. Logic lives in com.rdss.cdss.rules.*; codes must match.
-- ---------------------------------------------------------------------------
INSERT INTO cdss_rule (code, name, category, description, rule_version, validated_method, parameters) VALUES
('NEWS2', 'NEWS2 aggregate score', 'DETERIORATION',
 'Calculates the RCP NEWS2 score from the most recent complete observation set (respiratory rate, SpO2, air/oxygen, systolic BP, pulse, consciousness, temperature). SpO2 scale 2 is used only when a clinician has documented risk of hypercapnic respiratory failure. Not calculated when any parameter is missing, for patients under 16, or when observations are older than the configured window. Not validated in pregnancy.',
 '1.0.0', TRUE, '{"maxObservationAgeHours": 24}'),
('HYPOXAEMIA', 'SpO2 outside target range', 'OXYGENATION',
 'Compares the latest SpO2 with the clinician-documented target range, or the BTS default target (94-98%, or 88-92% when hypercapnic risk is documented). Flags values below target, and values above target while on supplemental oxygen in patients at risk of hypercapnic respiratory failure.',
 '1.0.0', FALSE, '{"defaultTargetLow": 94, "defaultTargetHigh": 98, "severeMarginBelowTarget": 4}'),
('RESP_RATE', 'Abnormal respiratory rate', 'OBSERVATIONS',
 'Flags respiratory rates in the NEWS2 scoring bands >= 21/min or <= 11/min (single-parameter score of 3 at <= 8 or >= 25).',
 '1.0.0', FALSE, '{}'),
('COPD_SPIROMETRY', 'COPD spirometric assessment (GOLD)', 'COPD',
 'Uses the most recent post-bronchodilator spirometry: FEV1/FVC < 0.70 indicates persistent airflow obstruction; GOLD grade 1-4 from FEV1 % predicted (>=80, 50-79, 30-49, <30). Assigns GOLD group A/B/E from exacerbation history and mMRC/CAT when available. Reports missing spirometry for patients with documented COPD.',
 '1.0.0', TRUE, '{}'),
('ASTHMA_CONTROL', 'Asthma symptom control (GINA)', 'ASTHMA',
 'Scores GINA symptom control from four clinician-entered questions about the past 4 weeks (well controlled 0, partly controlled 1-2, uncontrolled 3-4). Also reports bronchodilator responsiveness meeting the GINA criterion (FEV1 increase > 12% and > 200 mL), which supports but does not establish a diagnosis of asthma.',
 '1.0.0', TRUE, '{}'),
('OBS_TREND', 'Short-term observation trend', 'TREND',
 'Compares the two most recent observation sets within the configured window and reports an SpO2 fall or respiratory-rate rise beyond the configured thresholds. Heuristic only: not a validated score.',
 '1.0.0', FALSE, '{"windowHours": 48, "spo2DropThreshold": 3, "respRateRiseThreshold": 5}'),
('HAEMOPTYSIS', 'Haemoptysis red flag', 'RED_FLAG',
 'When haemoptysis is documented in the encounter: for patients aged 40 or over, NICE NG12 recommends referral using a suspected cancer pathway if the haemoptysis is unexplained.',
 '1.0.0', FALSE, '{}'),
('DATA_QUALITY', 'Missing or inconsistent data', 'DATA_QUALITY',
 'Reports data that limits the assessment: no recent observations, oxygen use without flow/FiO2, COPD without documented hypercapnic-risk status, missing symptom scores.',
 '1.0.0', FALSE, '{}');

INSERT INTO cdss_rule_guideline (rule_id, guideline_id)
SELECT r.id, g.id FROM cdss_rule r JOIN guideline_reference g ON
    (r.code = 'NEWS2' AND g.code = 'RCP-NEWS2') OR
    (r.code = 'HYPOXAEMIA' AND g.code IN ('BTS-O2-2017', 'RCP-NEWS2')) OR
    (r.code = 'RESP_RATE' AND g.code = 'RCP-NEWS2') OR
    (r.code = 'COPD_SPIROMETRY' AND g.code = 'GOLD-2025') OR
    (r.code = 'ASTHMA_CONTROL' AND g.code IN ('GINA-2024', 'ERS-ATS-2022')) OR
    (r.code = 'HAEMOPTYSIS' AND g.code = 'NICE-NG12') OR
    (r.code = 'DATA_QUALITY' AND g.code IN ('RCP-NEWS2', 'BTS-O2-2017'));
