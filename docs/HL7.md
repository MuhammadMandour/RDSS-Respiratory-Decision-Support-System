# HL7 v2 interface specification (RDSS)

> **Compatibility:** RDSS has been tested only against its own automated test messages and HAPI's
> MLLP client and server (`Hl7IT`). It has **not** been certified or interface-tested with any
> specific EMR. Before go-live, validate every feed against the sending system's interface
> specification.

## Transports

| Transport | Endpoint | Authentication | Notes |
|---|---|---|---|
| HTTP | `POST /api/v1/hl7/messages`, body = ER7 text (`text/plain` or `application/hl7-v2`) | Header `X-Integration-Key: <HL7_INBOUND_API_KEY>` | The endpoint is disabled (503) while the key is empty. The response body is the HL7 ACK. |
| MLLP | TCP `HL7_MLLP_PORT` (default 2575) when `HL7_MLLP_ENABLED=true` | none (MLLP has no authentication) | Expose only on a trusted network segment or VPN. Uses the same pipeline as HTTP. |
| Outbound MLLP | system settings `hl7.outbound.host`/`port`, enabled with `hl7.outbound.enabled=true` | n/a | Sends ADT^A08 after a patient is created or updated in RDSS. |

Accepted versions are 2.3 to 2.5.1, parsed into v2.5 structures (HAPI 2.6.0, default validation).
Segment separators may be CR, LF or CRLF. MLLP framing bytes sent over HTTP are stripped.

## Processing and acknowledgements

1. MSH is read leniently first, so every message gets logged and answered.
2. **Idempotency:** a repeat of the same (MSH-4 sending facility, MSH-3 sending application,
   MSH-10 control ID) is not processed again. The original ACK is returned unchanged and the
   duplicate is counted.
3. The raw message is stored in `hl7_message`. Only administrators can see the content, and each
   view is audited.
4. The message is parsed and validated, routed by MSH-9 type and trigger, and applied in a
   single database transaction.

| ACK | Meaning | Retry |
|---|---|---|
| **AA** | Applied | n/a |
| **AE** | Business error, for example an unknown patient identifier, unknown clinician, scheduling conflict, value out of range, or FEV1 > FVC | No. An admin can fix the cause and use **Reprocess**. |
| **AE** | Temporary processing error, such as the database being unavailable | Automatic, with exponential backoff (1, 2, 4… min), up to `hl7.retry.max_attempts` (default 5) |
| **AR** | Unparseable, failed validation, missing MSH-10, or unsupported message type | No |

## Patient identity (PID-3)

* An identifier whose assigning authority (CX-4.1) equals the setting
  `hl7.rdss_assigning_authority` (default `RDSS`) is an **RDSS MRN**.
* Any other authority, such as `CITYHOSP`, is looked up in `patient_identifier (system, value)`.
  ADT messages create these mappings.
* Identifiers **without** an assigning authority are ignored.
* An existing mapping is never moved to another patient. That case returns AE.

## Supported messages and field mappings

### ADT^A01 / A04 / A08: create or update a patient
| RDSS field | HL7 |
|---|---|
| identifiers | PID-3 (CX-1 ID, CX-4.1 assigning authority), **required** |
| last / first name | PID-5.1.1 / PID-5.2, **required** |
| date of birth | PID-7, **required**, must not be in the future |
| sex | PID-8: F→Female, M→Male, O/A/N→Other, anything else→Unknown |
| address | PID-11.1.1 street, .3 city, .5 postal code, .6 country |
| phone | PID-13.1 |

If no identifier matches an existing patient, a new patient is created with a new RDSS MRN.
Clinical data is never changed by ADT. **ADT^A03** (discharge) is acknowledged with no state
change, because RDSS does not model inpatient stays.

### ORM^O01: imaging orders
| | HL7 |
|---|---|
| order control | ORC-1: `NW` creates, `CA` cancels. Anything else returns AE. |
| placer order number | ORC-2.1, **required**. It is stored as the external order ID and is unique; a repeated NW is idempotent. |
| procedure | OBR-4.2 text (or OBR-4.1 code) |
| modality | OBR-24: CT, DX, CR, MR, US, PT or NM; anything else becomes OT |
| clinical indication | OBR-13 |

### ORU^R01: observations and spirometry
Only numeric OBX values coded with **LOINC** (OBX-3.3 = `LN`) are imported. Results with
OBX-11 = D or X are skipped.

| LOINC | RDSS field | Units |
|---|---|---|
| 9279-1 | respiratory rate | /min |
| 8867-4 | heart rate | /min |
| 59408-5, 2708-6 | SpO₂ | % |
| 8480-6 / 8462-4 | systolic / diastolic BP | mm[Hg] |
| 8310-5 | temperature | Cel, or `[degF]` converted |
| 20150-9 | FEV1 | L |
| 19870-5 | FVC | L |

The vitals from each OBR become one observation set, and FEV1 and FVC become one pulmonary
function test. The time comes from OBX-14, otherwise OBR-7, otherwise the receive time.
Out-of-range values are rejected with AE; they are never clipped. Records are attributed to the
disabled system account `system.hl7` and noted "Imported via HL7 ORU^R01 (control id …)".
Unsupported codes are listed in the log note.

### SIU^S12 / S15: appointments
| | HL7 |
|---|---|
| placer appointment ID | SCH-1.1, **required**, unique external ID; a repeated S12 is idempotent |
| start | AIS-4, otherwise SCH-11.4 |
| duration | AIS-7, with AIS-8 = MIN (or H); otherwise SCH-11.5 end; otherwise the default slot length |
| clinician | AIP-3.1 = the clinician's RDSS **username**; they must be an active clinician |
| reason | SCH-7.2 (or .1) |

S12 books a SCHEDULED consultation. Conflicts with existing bookings return AE, and the
database exclusion constraints also apply. S15 cancels the booking.

### Outbound ADT^A08
This message is sent when a patient is created or changed in RDSS and outbound is enabled. It
carries MSH (RDSS/RDSS), EVN, and PID with:
- PID-3: the RDSS MRN under the RDSS authority, plus all mapped external identifiers
- PID-5, PID-7, PID-8, PID-11 and PID-13

It also includes PV1-2 = N. Delivery is retried until the receiver returns AA or CA.

## Example (HTTP, PowerShell)

```powershell
$msg = "MSH|^~\&|EMR|CITYHOSP|RDSS|RDSS|20261016120000||ADT^A04^ADT_A01|MSG0001|P|2.5`r" +
       "EVN|A04|20261016120000`r" +
       "PID|1||EXT123^^^CITYHOSP^MR||Testperson^Avery||19800215|F`r"
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/hl7/messages `
  -Headers @{ "X-Integration-Key" = $env:HL7_INBOUND_API_KEY } -ContentType "application/hl7-v2" -Body $msg
```

Sample messages are in `tools/hl7-samples/`. Use synthetic data only.
