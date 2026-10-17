# Imaging (DICOM) integration

## Components

| Component | Role |
|---|---|
| **Orthanc** (`orthancteam/orthanc`) | DICOM store with the DICOMweb plugin. It requires basic auth and sits on the internal network only. |
| **Backend `imaging` package** | Orders, study/series metadata, findings, uploads and links to Orthanc, viewer sessions, and the DICOMweb proxy |
| **OHIF Viewer v3.13.13** (`viewer/`) | Official image, relocated to `/ohif/` and served on the same origin by the frontend nginx |

PostgreSQL stores identifiers and metadata only (`imaging_study`, `imaging_series_metadata`,
`imaging_finding`, `imaging_order`). Pixel data stays in Orthanc. Original DICOM files are
stored unmodified (`OVERWRITE_INSTANCES=false`).

## Workflow

1. **Order (optional).** A clinician creates an imaging order, which gets an accession number like `RDSS1000001`.
2. **Store.** There are two ways to bring a study in:
   - **Upload:** `POST /api/v1/patients/{id}/imaging-studies/upload` with multipart `files[]`. The
     backend authorizes the clinician, stores each file in Orthanc, then reads the study and
     series metadata back.
   - **Link:** `POST /api/v1/patients/{id}/imaging-studies/link` links a study that is already in
     Orthanc, for example one sent by a modality via DICOM C-STORE to port 4242.
3. **Association checks.**
   - A study already linked to another patient is rejected. Any instance this upload just added is
     removed from Orthanc again, so studies are never mixed across patients.
   - If the DICOM `PatientID` differs from the patient's MRN, the study is flagged `patientIdMismatch`
     and the UI warns about it.
   - Attaching a study to an order completes the order.
4. **View.** `POST /api/v1/imaging/viewer-sessions {studyIds}` checks read access to every study.
   All the studies must belong to the same patient, which allows current-versus-prior comparison.
   The endpoint then issues the `RDSS_VIEWER` cookie and returns
   `/ohif/viewer?StudyInstanceUIDs=...`. The cookie is HttpOnly, SameSite=Strict, scoped to the
   path `/api/v1/dicomweb`, signed, lists the granted StudyInstanceUIDs, and expires after 30 minutes.
5. **DICOMweb proxy** (`GET /api/v1/dicomweb/**`) validates the cookie and enforces
   `DicomWebAccessPolicy`, as follows:
   - `/studies/{uid}/...` is forwarded only for granted UIDs.
   - `/studies?StudyInstanceUID=...` must name only granted UIDs.
   - A `/studies?...` query **without** a UID filter, such as OHIF's "other studies of this
     patient" query, is **narrowed** to the granted UIDs before forwarding.
   - The numeric `0020000D` alias, root `/series` and `/instances`, and path tricks are refused.
   - Only GET is supported, so there is no STOW, delete or reject.
   - Orthanc's absolute BulkDataURIs are rewritten to the proxy.
6. **Findings.** Clinicians document respiratory findings on a study: opacity, consolidation,
   effusion, nodule, pneumothorax, and similar. Each records type, laterality, size and source
   (`CLINICIAN`, `RADIOLOGIST_REPORT` or `DEMONSTRATION`). Findings start PRELIMINARY and can be
   finalized or marked entered in error; they are never deleted. Patients see FINAL findings only.
   `AI_UNVALIDATED` exists in the schema for a future, separately documented inference service and
   cannot be entered by hand.
7. **CDSS.** The `IMAGING_FINDINGS` rule restates documented findings from the last 180 days in
   assessments, with their provenance. RDSS never derives findings from pixels.

## Viewer capabilities: what was actually verified

Verified on 2026-10-16 in headless Chromium with WebGL2, using the synthetic CT phantom
(80 × 256 × 256, 2.5 mm slices), by loading OHIF hanging protocols through the authorized proxy:

| Capability | Status | How verified |
|---|---|---|
| Axial stack viewing and slice navigation | ✅ | Default layout renders 80 images and shows the W/L from the DICOM header |
| MPR (axial, sagittal, coronal) | ✅ | `hangingprotocolId=mpr`: 3 viewports with rendered content |
| 3D volume rendering | ⚠️ renders | `hangingprotocolId=fourUp`: the 3D viewport renders. The default transfer-function preset is not tuned for the synthetic phantom and shows a solid box; real CT data behaves differently. Not evaluated on clinical data. |
| Side-by-side / historical comparison | ✅ | Opening current and prior CT lists both studies in the study browser |
| Zoom, pan, rotate, window/level, measurements, annotations | Provided by OHIF tools | Toolbar present; not individually automated |
| Saving viewer annotations or measurements | ❌ not supported | Viewer markup is per session. STOW-RS is blocked by design. Clinical notes and findings are saved in RDSS instead. |
| Sharpening, smoothing, noise reduction | Not verified | Depends on OHIF/Cornerstone3D features. All viewer processing is display-only, and the original DICOM is unchanged. |
| Chest radiograph (DX) | ✅ upload + metadata | Synthetic projection radiograph is stored and listed |

OHIF shows its own "for investigational use only" notice, which is appropriate for this system.

### How OHIF is relocated to `/ohif/`
The upstream image bakes `/` into its build in three places, and `viewer/Dockerfile` patches each one:
1. `index.html` asset URLs and `window.PUBLIC_URL`
2. the webpack runtime `__webpack_require__.p` in the app and worker bundles, which sets where
   chunks and codec workers load from
3. font `url(/...)` references in the CSS

Upstream serves the real assets from `.gz` files (`gzip_static always`), so the Dockerfile patches
the decompressed payloads and checks that no `"/"` public path remains. **When upgrading OHIF,**
rebuild the image and re-run the viewer check: open a study and confirm there are no console
errors and that the canvas has rendered.

## Synthetic demo data

`tools/synthetic-dicom/generate.py` generates procedural phantoms, so no real patient images are
involved. It produces:
- a current CT with a 9 mm right-lung nodule and a left effusion
- a prior CT without the effusion
- a projected chest radiograph

Run it inside Docker, with no host Python packages needed, and upload the results through the API:

```powershell
.\tools\load-demo-imaging.ps1          # Windows PowerShell (dev stack running)
./tools/load-demo-imaging.sh           # bash
```

## Limitations

* The viewer grant cookie is shared per browser. Opening a different set of studies replaces the
  grant, so an earlier viewer tab stops loading new images.
* Grants expire after 30 minutes. Disabling a user does not revoke a grant that is already open.
* An upload rejected part-way can leave unlinked instances in Orthanc. These are never reachable
  through the proxy, because grants only cover linked studies.
* Large uploads go through the backend (Tomcat `max-part-count` is 2010 and each file is limited to
  200 MB). Bulk archives should use DICOM C-STORE to Orthanc followed by **Link**.
