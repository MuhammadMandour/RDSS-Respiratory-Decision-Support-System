# RDSS REST API

Base path `/api/v1`. JSON in and out unless noted. The authoritative, always-current contract is the
OpenAPI document generated from the code:

* Swagger UI: http://localhost:18081/api/v1/docs (dev override, `API_DOCS_ENABLED=true`)
* OpenAPI JSON: http://localhost:18081/api/v1/openapi.json

This page is the human overview: authentication, conventions, and every endpoint grouped by module
with the roles that may call it. Authorization is enforced in the backend for every request
(`@PreAuthorize` plus resource-level checks in `PatientAccessPolicy`); the UI menu is cosmetic.

## Authentication

```powershell
$body = @{ username = 'dr.hale'; password = $env:DEMO_USER_PASSWORD } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/auth/login -ContentType 'application/json' -Body $body
$h = @{ Authorization = "Bearer $($login.accessToken)" }
Invoke-RestMethod http://localhost:8080/api/v1/patients -Headers $h
```

* Stateless JWT (HS256), lifetime `JWT_EXPIRATION` (default 8 h). Send `Authorization: Bearer <token>`.
* Repeated failed logins lock the account temporarily; the error message never says which part was wrong.
* HL7 inbound uses a separate shared key header, `X-Integration-Key` (see [HL7.md](HL7.md)).
* The DICOMweb proxy uses a short-lived, study-scoped HttpOnly cookie issued by `POST /imaging/viewer-sessions`
  (see [IMAGING.md](IMAGING.md)).

## Conventions

| Topic | Behaviour |
|-------|-----------|
| Errors | `{ timestamp, status, error, message, path, fieldErrors[] }`. 400 validation, 401 unauthenticated, 403 forbidden, 404 not found, 409 conflict (optimistic lock, double booking, duplicate), 422 business rule. |
| Patient isolation | A PATIENT requesting another patient's resource gets **404** (existence is not revealed). Staff outside the care team get **403**. |
| Paging | `?page=0&size=20` → `{ content, page, size, totalElements, totalPages }`. |
| Concurrency | Mutable records carry `version`; stale updates return 409. |
| Clinical corrections | Observations, PFTs, diagnoses and imaging findings are never deleted: they are marked `entered-in-error` with a reason. Finalized encounters are amended, not edited. |
| Dates | ISO-8601. Instants are UTC; report periods are local dates in the clinic time zone (`clinic.timezone`). |
| Auditing | Reads and writes of patient data, exports and denied access are written to `audit_log`. |

Roles: **A** = ADMIN, **C** = CLINICIAN (care-team scoped for patient data), **P** = PATIENT (own record only).

## Endpoints

### Authentication and users
| Method | Path | Roles |
|--------|------|-------|
| POST | `/auth/login` | public |
| POST | `/auth/register` | public (patient self-registration) |
| GET | `/auth/me` | any signed-in |
| POST | `/auth/change-password` | any signed-in |
| GET, POST | `/users` | A |
| GET, PUT | `/users/{id}` | A |
| POST | `/users/{id}/enable`, `/disable`, `/reset-password` | A |
| GET | `/clinicians` | any signed-in (directory for booking) |
| GET, POST | `/departments`; PUT `/departments/{id}` | read: signed-in; write: A |

### Patients
| Method | Path | Roles |
|--------|------|-------|
| GET | `/patients?q=` | A, C |
| GET | `/patients/me` | P |
| GET | `/patients/{id}` | A, C (care team), P (own) |
| POST | `/patients` | A, C (registering clinician joins the care team) |
| PUT | `/patients/{id}` | A, C |
| PATCH | `/patients/{id}/contact` | P (own contact details), A, C (care team) |
| POST | `/patients/{id}/deactivate`, `/activate` | A |
| GET, POST | `/patients/{id}/care-team` | A, C |
| DELETE | `/patients/{id}/care-team/{clinicianId}` | A |

### Respiratory record
| Method | Path | Roles |
|--------|------|-------|
| GET | `/patients/{patientId}/respiratory-history`, `/respiratory-history/versions` | A, C, P (own) |
| POST | `/patients/{patientId}/respiratory-history` (new version) | C |
| GET, POST | `/patients/{patientId}/observations` | read A, C, P (own); write C |
| POST | `/observations/{id}/entered-in-error` | C |
| GET, POST | `/patients/{patientId}/pulmonary-function-tests` | read A, C, P (own); write C |
| POST | `/pulmonary-function-tests/{id}/entered-in-error` | C |

### Encounters, treatment plans, prescriptions
| Method | Path | Roles |
|--------|------|-------|
| GET, POST | `/patients/{patientId}/encounters` | read A, C, P (own, finalized only); write C |
| GET | `/encounters/mine` | C |
| GET, PUT | `/encounters/{id}` | C (P read own finalized) |
| POST | `/encounters/{id}/finalize`, `/amend` | C |
| GET, POST | `/patients/{patientId}/treatment-plans`; PUT `/treatment-plans/{id}` | read A, C, P (own); write C |
| GET, POST | `/patients/{patientId}/prescriptions` | read A, C, P (own); write C |
| POST | `/prescriptions/{id}/discontinue`, `/complete` | C |

### Appointments
| Method | Path | Roles |
|--------|------|-------|
| GET, PUT | `/clinicians/{clinicianId}/availability` | read signed-in; write A, C |
| GET | `/clinicians/{clinicianId}/slots?date=` | signed-in |
| GET, POST | `/appointments` | A, C, P (patients create REQUESTED appointments for themselves) |
| GET | `/appointments/{id}` | A, C, P (own) |
| POST | `/appointments/{id}/reschedule`, `/cancel` | A, C, P (own, before the cancellation cut-off) |
| POST | `/appointments/{id}/status` | A, C |
| POST | `/appointments/{id}/encounter` | C (starts an encounter from the visit) |

Double booking of a clinician or patient is prevented by PostgreSQL exclusion constraints and returns 409.

### Decision support (CDSS)
| Method | Path | Roles |
|--------|------|-------|
| POST, GET | `/patients/{patientId}/cdss/assessments` | C (care team) |
| GET | `/cdss/assessments/{id}` | C (care team) |
| POST | `/cdss/assessments/{id}/review` | C (accept/dismiss each alert, dismissal requires a reason) |
| GET | `/cdss/rules`, `/cdss/guidelines` | A, C |
| PUT | `/cdss/rules/{code}` | A (enable/disable, tune documented parameters) |

Rules, thresholds and sources: [CDSS_RULES.md](CDSS_RULES.md).

### Diagnoses and ICD-10-CM
| Method | Path | Roles |
|--------|------|-------|
| GET | `/icd10?q=`, `/icd10/{code}` | signed-in |
| PUT | `/icd10/{code}/active` | A |
| POST | `/icd10/import` (multipart, CDC order file) | A |
| GET, POST | `/patients/{patientId}/diagnoses` | read A, C, P (own); write C |
| POST | `/diagnoses/{id}/resolve`, `/entered-in-error` | C |

### Imaging
| Method | Path | Roles |
|--------|------|-------|
| GET, POST | `/patients/{patientId}/imaging-orders`; POST `/imaging-orders/{id}/cancel` | read A, C, P (own); write C |
| GET | `/patients/{patientId}/imaging-studies` | A, C, P (own) |
| POST | `/patients/{patientId}/imaging-studies/upload` (multipart DICOM) | C |
| POST | `/patients/{patientId}/imaging-studies/link` | C |
| GET | `/imaging-studies/{id}` | A, C, P (own) |
| POST | `/imaging-studies/{id}/findings`; `/imaging-findings/{id}/finalize`, `/entered-in-error` | C |
| POST | `/imaging/viewer-sessions` | anyone who may read the study; sets the `RDSS_VIEWER` cookie |
| GET | `/dicomweb/**` | viewer cookie only; read-only QIDO/WADO restricted to the granted study |

### Billing
| Method | Path | Roles |
|--------|------|-------|
| GET | `/billing/services` | signed-in |
| POST, PUT | `/billing/services`, `/billing/services/{id}` | A |
| GET | `/invoices`, `/invoices/{id}` | A, C, P (own, non-draft) |
| POST, PUT | `/invoices`, `/invoices/{id}` (drafts) | A, C |
| POST | `/invoices/{id}/issue` | A, C |
| POST | `/invoices/{id}/void` | A |
| POST | `/invoices/{id}/payments`; `/payments/{id}/reverse` | A (records offline payments) |
| POST | `/invoices/{id}/simulated-payment` | P (own issued invoices) — **simulation only, no gateway** |

### HL7 v2
| Method | Path | Roles |
|--------|------|-------|
| POST | `/hl7/messages` (`text/plain` or `application/hl7-v2`) | `X-Integration-Key` header; returns the ACK |
| GET | `/hl7/status`, `/hl7/messages`, `/hl7/messages/{id}` | A |
| POST | `/hl7/messages/{id}/reprocess` | A |

### Reports and exports
| Method | Path | Roles |
|--------|------|-------|
| GET | `/reports/summary?from=YYYY-MM-DD&to=YYYY-MM-DD` | A (organisation-wide, includes billing and integration), C (care-team aggregates only) |
| GET | `/reports/exports` | A, C (lists datasets the caller may download) |
| GET | `/reports/exports/{dataset}?from=&to=` (CSV) | see below |

Period defaults to the last 30 days, maximum 731 days. Report responses contain aggregates only,
never patient-level rows. CSV files are UTF-8 with BOM, CRLF line endings, and cells beginning with
`= + - @` (or tab or carriage return) are prefixed with `'` to prevent spreadsheet formula injection. Every export is audited
(`REPORT_EXPORT`).

| Dataset | Roles | Content |
|---------|-------|---------|
| `appointments-daily` | A, C | date, status, count |
| `diagnoses` | A, C | ICD-10 code, description, version, type, status, count, distinct patients |
| `cdss-alerts` | A, C | rule, severity, disposition, count |
| `imaging-findings` | A, C | finding type, source, status, count |
| `revenue-by-service` | A | service, lines, quantity, subtotal, discount, tax, total |
| `invoices` | A | invoice number, status, dates, amounts, simulated payments (no patient names) |
| `hl7-messages` | A | message metadata and errors (never the raw message body) |

### Administration
| Method | Path | Roles |
|--------|------|-------|
| GET | `/audit?actor=&action=&resourceType=&patientId=&outcome=&from=&to=` | A |
| GET | `/settings`; PUT `/settings/{key}` | A (validated keys only) |
| GET | `/system/status` | public (no sensitive detail) |
