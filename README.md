# RDSS — Respiratory Decision Support System

Full-stack healthcare information platform for respiratory care: patients, encounters,
appointments, billing, DICOM imaging (Orthanc), HL7 v2 interoperability, ICD-10 coding
and a rule-based respiratory Clinical Decision Support System (CDSS).

> **Development system.** Not validated for clinical use. Decision-support output is
> advisory only; clinical decisions remain the responsibility of qualified professionals.
> Use synthetic data only.

* Architecture and design decisions: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
* Decision-support rules, sources and worked examples: [docs/CDSS_RULES.md](docs/CDSS_RULES.md)
* Imaging, viewer security and verified viewer capabilities: [docs/IMAGING.md](docs/IMAGING.md)
* HL7 v2 interface specification (profiles, field mappings, ACK/retry rules): [docs/HL7.md](docs/HL7.md)
* REST API overview with roles per endpoint: [docs/API.md](docs/API.md) (full OpenAPI in Swagger UI on the dev stack)

## Stack

| Layer     | Technology |
|-----------|------------|
| Backend   | Java 21, Spring Boot 3.5 (Web, Security, Data JPA, Validation, Actuator), Flyway, springdoc-openapi, JJWT |
| Frontend  | React 19, Vite, TypeScript, MUI, React Router, Axios, TanStack Query, Recharts |
| Data      | PostgreSQL 16 |
| Imaging   | Orthanc + DICOMweb plugin, OHIF Viewer v3 (served at `/ohif/`) |
| Interop   | HL7 v2 via HAPI 2.6 (HTTP + optional MLLP), ICD-10-CM catalogue with official-file importer |
| Runtime   | Docker Compose |

## Prerequisites (Windows)

* Docker Desktop (WSL 2 backend), running
* For local development without Docker: JDK 21, Maven 3.9+, Node.js 22+

## Quick start (Docker, PowerShell)

```powershell
# 1. Create your environment file and set real values for every CHANGE_ME
Copy-Item .env.example .env
notepad .env

# 2a. Production-oriented stack (only the web UI is published, on 127.0.0.1:8080)
docker compose up --build -d

# 2b. OR development stack (also publishes postgres/orthanc/backend on 127.0.0.1,
#     enables Swagger UI and synthetic demo data)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build -d

# 3. Check health
docker compose ps
Invoke-RestMethod http://localhost:8080/api/v1/system/status
```

Open http://localhost:8080.

Optional — load **synthetic** demo imaging (CT current/prior + chest radiograph) for the demo COPD
patient, generated in Docker and uploaded through the API:

```powershell
.\tools\load-demo-imaging.ps1
```

Database migrations run automatically at backend startup (Flyway). There is no manual
migration step; the applied history is in the `flyway_schema_history` table.

### Accounts

* **Development stack** (`SEED_DEMO_DATA=true`): synthetic demo accounts are created on first start.
  All share the password in `DEMO_USER_PASSWORD` (dev profile default `Demo-Pass-2026` when unset).

  | Username | Role | Notes |
  |----------|------|-------|
  | `admin` | Administrator | users, settings, audit log |
  | `dr.hale` | Clinician | care team of Jordan Reyes (asthma) and Samuel Okafor (COPD) |
  | `dr.osei` | Clinician | care team of Maria Novak (acute, abnormal vitals) and Daniel Kim |
  | `patient.jordan` | Patient | linked to Jordan Reyes |
  | `patient.samuel` | Patient | linked to Samuel Okafor |

  All demo people and values are invented. Patients can also self-register at `/register`.
* **Production-oriented stack**: set `BOOTSTRAP_ADMIN_PASSWORD` in `.env` before the first start to
  create the initial `admin` account (only when no administrator exists), then create staff
  accounts from **Users**. Remove the variable after the first login.

### HL7 samples

With `HL7_INBOUND_API_KEY` set in `.env`, send the synthetic sample messages (ADT, ORU, ORM, SIU):

```powershell
.\tools\send-hl7.ps1
```

### Ports

| Service  | Production file | Dev override (127.0.0.1 only) |
|----------|-----------------|-------------------------------|
| Web UI (nginx → SPA + `/api` proxy) | `127.0.0.1:8080` | same |
| Backend API / Swagger UI | not published | `18081` → http://localhost:18081/api/v1/docs |
| PostgreSQL | not published | `15432` |
| Orthanc HTTP (basic auth) | not published | `18042` |
| Orthanc DICOM (C-STORE) | not published | `14242` |
| HL7 MLLP listener | not published | `12575` |

Override any of them in `.env` (`RDSS_HTTP_PORT`, `DEV_BACKEND_PORT`, `DEV_POSTGRES_PORT`,
`DEV_ORTHANC_HTTP_PORT`, `DEV_ORTHANC_DICOM_PORT`).

### Stop / reset

```powershell
docker compose down            # stop, keep data (volumes persist)
docker compose down -v         # stop AND delete all PostgreSQL + Orthanc data
```

## Local development without Docker for the app

Run only the infrastructure in Docker, and the backend/frontend on the host:

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres orthanc

# Backend (new terminal) - reads the same values that .env provides to Docker
cd backend
$env:DB_URL = "jdbc:postgresql://localhost:15432/rdss"
$env:DB_PASSWORD = "<POSTGRES_PASSWORD from .env>"
$env:ORTHANC_URL = "http://localhost:18042"
$env:ORTHANC_PASSWORD = "<ORTHANC_PASSWORD from .env>"
$env:SERVER_PORT = "18081"
mvn spring-boot:run          # dev profile is the default

# Frontend (new terminal) - Vite proxies /api to http://localhost:18081
cd frontend
npm install
npm run dev                  # http://localhost:5173
```

## Tests

```powershell
# Backend: unit + integration tests. Integration tests start PostgreSQL via
# Testcontainers, so Docker Desktop must be running.
cd backend
mvn test

# Frontend: type check + component/API tests (Vitest + Testing Library + MSW)
cd frontend
npm run typecheck
npm test

# End-to-end (Playwright, Chromium) against the running dev stack with demo data.
# Reads DEMO_USER_PASSWORD from .env (or set $env:E2E_PASSWORD; $env:E2E_BASE_URL defaults to http://localhost:8080).
cd e2e
npm install
npx playwright install chromium   # first time only
npm test                          # HTML report: npx playwright show-report
```

The E2E suite covers sign-in/out, patient isolation (UI and API), a patient requesting and
cancelling an appointment, a clinician running and reviewing a CDSS assessment, ICD-10 coding,
care-team boundaries, role-scoped reports with CSV download, and admin finance/HL7/audit views.
It creates a small amount of synthetic data (assessments, a diagnosis, a cancelled appointment)
on each run.

### Deployment verification

```powershell
# Health, endpoints, access control, published ports, non-root containers
.\tools\verify-stack.ps1 -Dev                  # dev stack
.\tools\verify-stack.ps1                       # production-oriented stack

# ...plus persistence: docker compose down (volumes kept) + up, then compare
# patient/user/audit counts, schema version and Orthanc study count
.\tools\verify-stack.ps1 -Dev -RestartTest

# Clean-checkout deployment: copies the tree without build output or .env, generates fresh
# secrets, builds the production compose file as project "rdss-clean" on port 18090, verifies it
# (bootstrap admin, no demo data), then removes its containers, volumes and folder.
.\tools\clean-deploy-test.ps1
```

If script execution is blocked, run them with
`powershell -ExecutionPolicy Bypass -File .\tools\verify-stack.ps1 -Dev`.

## Demo workflows (dev stack)

1. **Clinician review** — sign in as `dr.hale`, open *Patients → Samuel Okafor*. Review the
   respiratory tab (observations trend, PFTs), then *Decision support → Run assessment*. Accept or
   dismiss each alert (dismissal needs a reason) and complete the review. Every alert shows its rule,
   guideline source and the input values it used.
2. **Coding and billing** — on the patient's *Diagnoses* tab add an ICD-10-CM code via search. As
   `admin` (or the clinician), create an invoice on the *Billing* tab, add services and diagnosis
   codes, and issue it. Sign in as `patient.samuel` and use *Pay now (simulated)*; the payment is
   labelled as simulated everywhere.
3. **Imaging** — after `.\tools\load-demo-imaging.ps1`, open Samuel's *Imaging* tab, launch the
   viewer for the CT, switch to MPR, or compare current/prior. Record a finding.
4. **Appointments** — as `patient.jordan` request an appointment; as `dr.hale` confirm it, check in
   and start an encounter from it. Booking an overlapping slot is refused.
5. **Integration** — run `.\tools\send-hl7.ps1` and inspect *HL7 integration* as `admin` (ACKs,
   the duplicate replay and the resulting patient, order, result and appointment).
6. **Reports** — *Reports* as `admin` (organisation-wide including billing and HL7) and as
   `dr.hale` (care-team aggregates only), change the period and download the CSV exports.
7. **Access control** — as `patient.jordan`, open `/patients/2` or call
   `GET /api/v1/patients/2`: the UI blocks it and the API answers 404. The attempt appears under
   *Audit log* as DENIED.

## Known limitations

* **Not a medical device.** Not clinically validated. Decision support is rule-based and advisory;
  rules marked "not validated" in *Decision rules* have not been checked against outcome data.
* **No AI model.** The optional inference panel (`CDSS_MOCK_INFERENCE=true`) is a labelled
  placeholder that performs no analysis.
* **Payments are simulated.** No payment gateway is integrated, and no money moves.
* **HL7** was tested only against the bundled synthetic messages and HAPI's own MLLP client and
  server. It has not been interface-tested with a real EMR. The MLLP listener has no authentication;
  enable it only on a trusted network. Outbound ADT^A08 is off unless configured.
* **ICD-10-CM** ships as a curated respiratory-focused subset (FY2025). The full code set can be
  imported from the official CDC order file; nothing beyond that is invented.
* **Imaging**: axial viewing, MPR and current/prior comparison are verified with synthetic data.
  3D volume rendering displays, but its default preset is not tuned (see [docs/IMAGING.md](docs/IMAGING.md)).
  Synthetic phantoms are not clinical images.
* **Security hardening still needed for real deployment:** TLS termination in front of port 8080,
  MFA, refresh-token rotation and server-side session revocation (tokens are short-lived
  bearer tokens held in `sessionStorage`), secret management beyond `.env`, and backups for the
  `postgres-data` and `orthanc-data` volumes.
* **Reports** are computed live from the operational tables. That suits this scale; a large
  deployment would need a reporting replica or warehouse. All dates use a single clinic time zone.

## Project layout

```
backend/     Spring Boot API (feature packages under com.rdss.*), Flyway migrations
frontend/    React SPA (feature folders under src/), nginx config for the container
viewer/      OHIF viewer image (served under /ohif/)
e2e/         Playwright end-to-end tests
tools/       Synthetic DICOM generator, demo loaders, HL7 samples, verification scripts
docs/        Architecture, API, CDSS rules, imaging, HL7 profiles
docker-compose.yml       production-oriented stack
docker-compose.dev.yml   development override
.env.example             configuration template (no real secrets)
```

## Security notes

* Secrets come only from environment variables (`.env`, never committed).
* PostgreSQL and Orthanc are on an internal Docker network with no published ports in the
  production file. The browser never talks to Orthanc directly.
* Containers run as non-root where the image supports it (backend, frontend). The official
  Orthanc and PostgreSQL images manage their own users.
* Place a TLS-terminating reverse proxy in front of port 8080 before any non-local use.
