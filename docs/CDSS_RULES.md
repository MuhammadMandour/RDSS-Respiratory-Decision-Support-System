# Respiratory CDSS — rule catalogue

Engine version **1.0.0**. Code: `backend/src/main/java/com/rdss/cdss/`. Tests that pin every
example below: `News2CalculatorTest`, `CdssRulesTest`, `CdssAssessmentIT`.

> Decision support only. Rules implement specific, cited criteria. They do not establish a
> diagnosis, and every finding requires clinician review. The engine has not been clinically
> validated as a whole system.

## How an assessment works

1. A clinician on the patient's care team runs an assessment, optionally linked to an encounter.
   They can also enter the GINA asthma-control answers.
2. `AssessmentInputCollector` freezes the current record into a `RuleContext`. The context
   holds demographics, the latest respiratory-history version, the two most recent observation
   sets, the latest PFT and latest post-bronchodilator PFT, and encounter symptoms. Entries
   marked *entered in error* are excluded.
3. `CdssEngine` runs every **enabled** rule from the `cdss_rule` registry with its parameters. A
   failing rule is recorded as `ERROR` and does not block the others.
4. The assessment is stored immutably with these fields:
   - `input_snapshot`: the exact inputs used.
   - `rules_applied`: the rule code, version and outcome for each rule.
   - `missing_data`
   - the NEWS2 result
   - one `cdss_alert` row per finding, with its explanation, evidence values and guideline codes.
5. **Review** is a separate step. The clinician accepts or dismisses every alert, and a dismissal
   needs a reason. They can add a review note. A completed review cannot be overwritten.

Machine-learning inference is a separate `InferenceProvider` interface whose output is stored in
`inference_output`, never mixed into rule findings. The only implementation is
`MockInferenceProvider`. It is off by default (`CDSS_MOCK_INFERENCE=true` turns it on) and is
labelled *DEMONSTRATION ONLY*. It performs no analysis.

## Severity scale

`INFO < LOW < MEDIUM < HIGH < CRITICAL`. Only NEWS2 maps severity from a validated risk band;
the other severities are display priorities, not validated risk estimates.

## Rules

### NEWS2 — `NEWS2` (validated method)
Source: RCP, *National Early Warning Score 2* (2017).

| Parameter | 3 | 2 | 1 | 0 | 1 | 2 | 3 |
|---|---|---|---|---|---|---|---|
| Resp. rate | ≤8 | | 9–11 | 12–20 | | 21–24 | ≥25 |
| SpO₂ scale 1 | ≤91 | 92–93 | 94–95 | ≥96 | | | |
| SpO₂ scale 2 | ≤83 | 84–85 | 86–87 | 88–92 / ≥93 on air | 93–94 on O₂ | 95–96 on O₂ | ≥97 on O₂ |
| Air or oxygen | | Oxygen | | Air | | | |
| Systolic BP | ≤90 | 91–100 | 101–110 | 111–219 | | | ≥220 |
| Pulse | ≤40 | | 41–50 | 51–90 | 91–110 | 111–130 | ≥131 |
| Consciousness | | | | Alert | | | CVPU (new confusion) |
| Temperature | ≤35.0 | | 35.1–36.0 | 36.1–38.0 | 38.1–39.0 | ≥39.1 | |

Risk bands are as follows. Total 0–4 is LOW. Any single parameter scoring 3 is LOW_MEDIUM. Total
5–6 is MEDIUM, and 7 or more is HIGH. These map to severities INFO/LOW, MEDIUM, HIGH and CRITICAL
respectively. The finding's suggestion reproduces the RCP escalation table.

Eligibility and limitations, as implemented:
* Not calculated under age 16, when any of the seven parameters is missing, or when the latest
  observations are older than `maxObservationAgeHours` (default 24).
* SpO₂ **scale 2** is used only when the clinician has documented risk of hypercapnic
  respiratory failure in the respiratory history.
* Confusion is scored as new confusion (3). The explanation asks the clinician to verify it is new.
* Not validated in pregnancy; RDSS does not record pregnancy, so this is stated in every NEWS2 finding.

| Example | Input | Output |
|---|---|---|
| Acute presentation (demo "Maria Novak") | RR 24, SpO₂ 92 air, SBP 102, HR 112, Alert, 38.6 °C | 8, HIGH, CRITICAL |
| Single red parameter | RR 26, otherwise normal | 3, LOW_MEDIUM |
| Scale 2 vs 1 | SpO₂ 90 on O₂, RR 18, SBP 130, HR 80, Alert, 37.0 °C | Scale 2 gives 2. Scale 1 gives 5. |
| Under 16 | age 15 | not calculated, recorded in missing data |

### SpO₂ outside target — `HYPOXAEMIA`
Source: BTS 2017 oxygen guideline. The target range is chosen in this order: the
clinician-documented range, then 88–92% if hypercapnic risk is documented, then 94–98%
(configurable). Below the target gives MEDIUM, or HIGH when it is at least
`severeMarginBelowTarget` (4) points below. Above the target **while on oxygen** gives MEDIUM
when hypercapnic risk is documented, otherwise LOW.

| Input | Output |
|---|---|
| SpO₂ 92 on air, no history | MEDIUM, target 94–98 (BTS default) |
| SpO₂ 89 on air, no history | HIGH |
| COPD + hypercapnic risk, SpO₂ 97 on O₂ | MEDIUM, "above target range on oxygen" |
| Documented target 92–96, SpO₂ 93 | no finding |

### Respiratory rate — `RESP_RATE`
NEWS2 bands: ≤8 or ≥25 → HIGH; 21–24 → MEDIUM; 9–11 → LOW; 12–20 → no finding.

### COPD spirometry — `COPD_SPIROMETRY` (validated criteria)
Source: GOLD 2025 report.
* It uses the latest **post-bronchodilator** PFT. FEV₁/FVC below 0.70 indicates persistent
  airflow obstruction.
* Grade by FEV₁ % predicted: GOLD 1 is 80 or above, GOLD 2 is 50–79, GOLD 3 is 30–49, and
  GOLD 4 is below 30.
* Group by exacerbation history and symptoms. **E** applies with 2 or more exacerbations, or 1
  or more hospitalisations, in the past 12 months. Otherwise **B** applies with mMRC of 2 or more
  or CAT of 10 or more, and **A** if neither. Exacerbation counts come from the respiratory
  history, and mMRC/CAT from the linked encounter.
* Documented COPD with no post-BD spirometry is reported in missing data.
* Obstruction without documented COPD gives a MEDIUM finding suggesting evaluation.
* Limitation: the fixed ratio can over-diagnose obstruction in older adults, and this is stated
  in every finding.

| Input | Output |
|---|---|
| Demo "Samuel Okafor": FEV₁ 1.45 / FVC 2.90 = 0.50, 52% pred, 2 exacerbations | GOLD 2, group E, INFO |
| 0 exacerbations, mMRC 1, CAT 15, FEV₁ 72% | group B |
| No COPD documented, ratio 0.60 | MEDIUM "evaluate" |

### Asthma — `ASTHMA_CONTROL` (validated questionnaire)
Source: GINA 2024. The rule uses four yes/no questions about the past 4 weeks, entered by the
clinician. A score of 0 is well controlled (INFO), 1–2 is partly controlled (LOW), and 3–4 is
uncontrolled (MEDIUM). If asthma is documented but the answers are incomplete, this is reported
in missing data. When the FEV₁ increase is more than 12% **and** more than 200 mL after
bronchodilator, the rule reports variable airflow (INFO), which "supports but does not
establish" asthma. The finding notes that the ERS/ATS 2022 criterion differs.

### Observation trend — `OBS_TREND` (heuristic, **not validated**)
This rule compares the two most recent observation sets taken within `windowHours` (48). It
reports a MEDIUM finding when SpO₂ falls by at least `spo2DropThreshold` (3) or the respiratory
rate rises by at least `respRateRiseThreshold` (5). The explanation always states that it is not
a validated score.

### Haemoptysis — `HAEMOPTYSIS`
Source: NICE NG12. When haemoptysis is documented in the linked encounter:
- Age 40 or over gives a HIGH finding. It cites NG12's recommendation to refer unexplained
  haemoptysis using a suspected-cancer pathway.
- Under 40 gives a MEDIUM finding, stating that the age criterion does not apply.

### Data quality — `DATA_QUALITY`
The rule records the following in missing data:
- no observations, or observations older than 24 h
- SpO₂ recorded without stating air or oxygen
- no respiratory history
- dyspnoea without an mMRC grade or CAT score

It raises INFO or LOW findings for:
- oxygen use without a flow rate or FiO₂
- COPD without a documented hypercapnic-risk assessment, in which case scale 1 and a 94–98%
  target were used

## Configuration

Administrators can enable or disable rules and change **only the parameters listed above** from
*Decision rules*, or with `PUT /api/v1/cdss/rules/{code}`. Each change is audited. NEWS2 scoring
bands are fixed by the standard and cannot be configured.

## Changing a rule

1. Change the Java implementation and bump `version()`.
2. Add a Flyway migration that updates `cdss_rule.rule_version`, plus the description and
   guidelines if needed.
3. Add or adjust the documented examples in `CdssRulesTest` and in this file.

Stored assessments keep the rule versions they were produced with.
