# RDSS Architecture

Respiratory Decision Support System — architecture, file layout and design decisions.

> **Status:** development system. Not validated for clinical use. All clinical
> decisions remain the responsibility of qualified healthcare professionals.

## 1. Runtime topology

```
                    host (127.0.0.1 only by default)
                            │ :8080
                 ┌──────────▼───────────┐      ┌──────────────┐
                 │ frontend (nginx)     │      │ viewer (OHIF)│
                 │  /        → SPA      │      │  /ohif/      │
                 │  /ohif/*  → viewer ──┼─────▶└──────────────┘
                 │  /api/*   → backend  │  reverse proxy, same origin
                 └──────────┬───────────┘
              network: edge │
                 ┌──────────▼───────────┐
                 │ backend (Spring Boot)│  REST /api/v1, JWT auth,
                 │  :8080 (internal)    │  authorization, audit, CDSS,
                 └───┬──────────────┬───┘  HL7 adapter, DICOMweb proxy
       network:      │              │
       internal ┌────▼─────┐  ┌─────▼──────┐
                │ postgres │  │ orthanc    │  DICOM store, DICOMweb plugin,
                │ :5432    │  │ :8042 HTTP │  basic-auth, never exposed
                └──────────┘  └────────────┘  to the browser directly
```

* The browser only talks to `frontend`. All API and imaging traffic goes through
  `/api/...`, which nginx forwards to `backend`.
* `postgres` and `orthanc` sit on an `internal` Docker network that has no
  published ports in the production-oriented compose file. The dev override
  (`docker-compose.dev.yml`) publishes them on `127.0.0.1` for debugging.
* The backend is the only component holding Orthanc credentials. Image retrieval
  (DICOMweb WADO-RS / QIDO-RS) is proxied by the backend after a per-study
  authorization check, so the viewer never gets unrestricted Orthanc access.

## 2. Backend (`backend/`)

Java 21, Spring Boot 3.5, Maven. Feature-based packages under `com.rdss`:

| Package        | Responsibility |
|----------------|----------------|
| `config`       | Security filter chain, CORS, OpenAPI, Jackson, typed `@ConfigurationProperties` |
| `common`       | Error model (`ApiError`), global exception handler, base entity, paging DTO |
| `system`       | System info / integration status endpoint |
| `auth`         | Login, registration, JWT issue/validation, current-user resolution |
| `users`        | User + role administration, clinician profiles |
| `patients`     | Patient registry, demographics, access policy (`PatientAccessPolicy`) |
| `respiratory`  | Respiratory history, observations (vitals), pulmonary function tests |
| `encounters`   | Clinical encounters, treatment plans, prescriptions |
| `appointments` | Availability slots, booking, conflict prevention |
| `diagnoses`    | ICD-10 code catalogue + encounter diagnoses |
| `cdss`         | Rule engine (deterministic), rules, guideline references, assessments, alerts |
| `imaging`      | Imaging studies/series/findings metadata, Orthanc client, DICOMweb proxy |
| `billing`      | Service catalogue, invoices, items, payments (simulated) |
| `hl7`          | HL7 v2 adapter (HAPI), inbound/outbound message log, ACKs, retries |
| `reports`      | Aggregate dashboards (role-scoped in SQL) + audited CSV export |
| `audit`        | Audit log writer + query API |

Each feature package contains `*Controller` (thin, HTTP only), `*Service`
(transactions + business logic), `*Repository` (Spring Data JPA), JPA entities,
and request/response DTOs (Java records). Entities are never serialized directly.

Cross-cutting rules:

* **Errors** — every error is returned as RFC 7807-style `ApiError`
  (`timestamp, status, error, message, path, fieldErrors[]`).
* **Security** — stateless JWT (HS256, secret from env). Request-level rules in
  `SecurityConfig`, method-level `@PreAuthorize` on services/controllers,
  resource-level checks through `PatientAccessPolicy` (a patient user may only
  ever resolve their own `patient_id`, regardless of the id in the URL).
* **Persistence** — PostgreSQL 16, Flyway migrations in
  `src/main/resources/db/migration`, `ddl-auto=validate` (Hibernate never alters
  the schema). Finalized clinical records are immutable; amendments create a new
  version row linked to the previous one.
* **Audit** — reads and writes of patient-level resources produce `audit_log`
  rows (actor, action, resource type/id, outcome). No PHI payloads, passwords or
  tokens are logged.
* **API docs** — springdoc-openapi at `/api/v1/docs` (Swagger UI) and
  `/api/v1/openapi.json`.

## 3. Frontend (`frontend/`)

React 19 + Vite + TypeScript, MUI, React Router, Axios, TanStack Query, Recharts.

```
src/
  app/          App shell, router, providers, theme, role-aware navigation
  shared/       api client (axios instance + typed helpers), components, hooks, utils
  auth/         login, registration, auth context, route guards
  dashboard/    role-specific dashboards
  patients/     directory, details, registration
  respiratory/  respiratory profile, observations, PFTs
  encounters/   encounter + assessment form, prescriptions, treatment plans
  appointments/ calendar and management
  billing/      catalogue, invoices, payments, printable view
  cdss/         assessment results, explanation panels, history, review
  imaging/      study list, viewer integration, findings
  reports/      analytics + CSV export
  settings/     user management, system settings, integration status
```

Every feature has an `api.ts` exporting typed functions + TanStack Query hooks;
types mirror backend DTOs in `types.ts`. Hiding a menu item is cosmetic only — the
backend enforces all authorization.

## 4. Clinical decision support design

* Rules are **deterministic Java implementations** of a `CdssRule` interface,
  each with an id, version, and linked `guideline_reference` rows. Rule
  metadata (enabled flag, thresholds where configurable) lives in `cdss_rule`.
* Each assessment stores a snapshot of the exact inputs used, rule versions,
  findings, explanations, missing data, and suggested next steps — immutable once
  created; clinician acknowledgment is a separate state transition.
* NEWS2 is implemented per the RCP 2017 chart including SpO₂ Scale 2 only when
  the clinician has documented hypercapnic respiratory failure target ranges,
  and is flagged as not validated for under-16s or pregnancy.
* Machine-learning inference is a separate optional interface
  (`InferenceProvider`); only a clearly labelled mock implementation exists.

## 5. Imaging

* Orthanc (`orthancteam/orthanc`) with DICOMweb plugin, basic auth, persistent volume.
* Backend uploads to Orthanc's REST API, reads tags, and records
  `imaging_study` / `imaging_series` metadata linked to patient (+ optional
  encounter/order).
* Viewer: **OHIF v3** (decided in Phase 4) for its verified MPR, 3D and comparison support, in its
  own container served same-origin at `/ohif/`. It fetches DICOMweb only through the backend
  proxy, which authorizes each request against a study-scoped, HttpOnly viewer cookie. See
  [IMAGING.md](IMAGING.md).

## 6. HL7 v2

* `hl7` package isolates HAPI HL7 v2 (parsing, validation, ACK generation) behind
  `Hl7MessageHandler` implementations per message type (ADT, ORM, ORU, SIU).
* Every inbound message is persisted first (`hl7_message`, keyed by sending
  facility + control id for idempotency), then processed; failures are retried
  with backoff up to a configurable limit. Transport: HTTP endpoint in the
  backend (MLLP listener optional and documented separately).

## 7. ICD-10

The app ships a small, explicitly versioned **subset** of ICD-10-CM respiratory
chapter (J00–J99) codes, sourced from the CDC/NCHS public-domain ICD-10-CM
release, with the release year recorded per row. Administrators can import a
full release file; the app does not fabricate a complete database.

## 8. Environments

| File | Purpose |
|------|---------|
| `docker-compose.yml` | Production-oriented: only `frontend` published on `127.0.0.1`, internal network for db/orthanc, `prod` Spring profile |
| `docker-compose.dev.yml` | Dev override: publishes postgres/orthanc/backend on `127.0.0.1`, `dev` profile, demo seed data |
| `.env.example` | All configurable values, no real secrets |

## 9. Phase plan

1. Foundation — compose, backend + frontend skeletons, Flyway, health, typed API.
2. Auth + core healthcare — users/roles, patients, encounters, observations, appointments.
3. CDSS — rules, assessments, alerts, review workflow.
4. Imaging — Orthanc, viewer, study linkage, findings.
5. Billing + interoperability — invoices, payments, ICD-10, HL7.
6. Analytics + finalization — dashboards, CSV, E2E tests, docs.

All six phases are implemented; see the README for verification commands and known limitations.

## 10. Reporting and analytics

* `reports.ReportService` computes aggregates with SQL directly over the operational tables
  (`NamedParameterJdbcTemplate`); there is no separate warehouse. At demonstration scale this is
  fast enough, and it avoids a second copy of patient data.
* **Scoping is done in SQL, not in the UI.** Administrators get organisation-wide figures plus the
  billing and integration sections. For clinicians, every query is restricted with
  `patient_id IN (SELECT patient_id FROM patient_care_team WHERE clinician_user_id = :me)`, and the
  billing/integration sections are omitted from the response entirely. Patients cannot call the API.
* Responses contain counts and amounts only — never patient-level rows — so a clinician cannot use
  reports to learn about patients outside their care team.
* Periods are local dates in the clinic time zone (`clinic.timezone`) and are converted to
  `[from 00:00, to+1 00:00)` instants; the maximum span is 731 days.
* CSV exports (`ReportExportService`) are aggregate datasets for both roles plus admin-only
  finance/integration datasets without patient names or raw HL7 bodies. `CsvWriter` follows RFC 4180
  and neutralises spreadsheet formula prefixes. Each export is audited as `REPORT_EXPORT`, and
  attempts to download an admin-only dataset are audited as `DENIED`.
* The frontend `reports/ReportsPage` renders KPI cards, Recharts charts, searchable tables and
  export buttons. The CSV download is fetched with the bearer token and saved via a Blob URL,
  because a plain link would not carry the token.

## 11. Testing and verification

| Layer | Tooling | Location |
|-------|---------|----------|
| Backend unit | JUnit 5, AssertJ | `backend/src/test/**/*Test.java` (NEWS2, rules, billing maths, CSV, HL7 parsing, ICD importer, slots) |
| Backend integration | Spring Boot Test, MockMvc, Testcontainers PostgreSQL 16 | `backend/src/test/**/*IT.java` (auth, patient isolation, conflicts, encounters, CDSS, imaging, billing, HL7, reports) |
| Frontend | Vitest, Testing Library, MSW | `frontend/src/**/*.test.tsx` |
| End-to-end | Playwright (Chromium) against the running stack | `e2e/tests/*.spec.ts` |
| Deployment | PowerShell scripts | `tools/verify-stack.ps1`, `tools/clean-deploy-test.ps1` |

Integration tests run the real Flyway migrations against a real PostgreSQL, so constraint-level
behaviour (exclusion constraints, check constraints, schema validation) is exercised. MockMvc does
not go through Tomcat, so container limits such as the multipart part count are covered by the
manual or E2E imaging upload instead.
