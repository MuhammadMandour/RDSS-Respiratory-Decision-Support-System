package com.rdss.imaging;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.ConflictException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;
import com.rdss.encounters.EncounterService;
import com.rdss.imaging.ImagingDtos.FindingRequest;
import com.rdss.imaging.ImagingDtos.FindingResponse;
import com.rdss.imaging.ImagingDtos.LinkStudyRequest;
import com.rdss.imaging.ImagingDtos.OrderRequest;
import com.rdss.imaging.ImagingDtos.OrderResponse;
import com.rdss.imaging.ImagingDtos.SeriesResponse;
import com.rdss.imaging.ImagingDtos.StudyResponse;
import com.rdss.imaging.ImagingDtos.StudySummary;
import com.rdss.imaging.ImagingDtos.UploadResult;
import com.rdss.imaging.model.ImagingFinding;
import com.rdss.imaging.model.ImagingFindingRepository;
import com.rdss.imaging.model.ImagingOrder;
import com.rdss.imaging.model.ImagingOrderRepository;
import com.rdss.imaging.model.ImagingSeries;
import com.rdss.imaging.model.ImagingSeriesRepository;
import com.rdss.imaging.model.ImagingStudy;
import com.rdss.imaging.model.ImagingStudyRepository;
import com.rdss.imaging.orthanc.ImagingArchive;
import com.rdss.imaging.orthanc.ImagingArchive.StoredInstance;
import com.rdss.imaging.orthanc.ImagingArchive.StudyMetadata;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientAccessPolicy;
import com.rdss.patients.PatientAccessPolicy.Access;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Imaging workflow: orders, storing/linking studies in Orthanc, metadata, findings.
 * Every operation is authorized against the owning patient before Orthanc is touched.
 */
@Service
public class ImagingService {

    static final int MAX_FILES_PER_UPLOAD = 2000;

    private final ImagingArchive archive;
    private final ImagingStudyRepository studies;
    private final ImagingSeriesRepository series;
    private final ImagingFindingRepository findings;
    private final ImagingOrderRepository orders;
    private final EncounterService encounters;
    private final AppUserRepository users;
    private final PatientAccessPolicy access;
    private final AuditService audit;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ImagingService(ImagingArchive archive, ImagingStudyRepository studies, ImagingSeriesRepository series,
                          ImagingFindingRepository findings, ImagingOrderRepository orders, EncounterService encounters,
                          AppUserRepository users, PatientAccessPolicy access, AuditService audit) {
        this.archive = archive;
        this.studies = studies;
        this.series = series;
        this.findings = findings;
        this.orders = orders;
        this.encounters = encounters;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    // ---- Orders ----

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public OrderResponse createOrder(Long patientId, OrderRequest req) {
        access.require(patientId, Access.CLINICAL_WRITE);
        if (req.encounterId() != null) {
            encounters.requireEditableForPatient(req.encounterId(), patientId);
        }
        String accession = "RDSS" + orders.nextAccessionNumber();
        ImagingOrder o = orders.save(new ImagingOrder(patientId, req.encounterId(), accession, req.modality(),
                req.bodyPart() == null || req.bodyPart().isBlank() ? "CHEST" : req.bodyPart().trim().toUpperCase(),
                req.procedureDescription().trim(), trim(req.clinicalIndication()),
                req.priority() == null ? ImagingOrder.Priority.ROUTINE : req.priority(), CurrentUser.require().id()));
        audit.success("CREATE", "ImagingOrder", o.getId(), patientId);
        return toResponse(o, names(List.of(o.getOrderedBy())));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders(Long patientId) {
        access.require(patientId, Access.CLINICAL_READ);
        List<ImagingOrder> list = orders.findByPatientIdOrderByOrderedAtDesc(patientId);
        Map<Long, AppUser> byId = names(list.stream().map(ImagingOrder::getOrderedBy).toList());
        return list.stream().map(o -> toResponse(o, byId)).toList();
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public OrderResponse cancelOrder(Long orderId) {
        ImagingOrder o = orders.findById(orderId).orElseThrow(() -> NotFoundException.of("Imaging order", orderId));
        access.require(o.getPatientId(), Access.CLINICAL_WRITE);
        o.cancel();
        audit.success("CANCEL", "ImagingOrder", orderId, o.getPatientId());
        return toResponse(o, names(List.of(o.getOrderedBy())));
    }

    // ---- Studies ----

    /**
     * Stores DICOM files in Orthanc and links the resulting studies to the patient. If a file
     * belongs to a study already linked to another patient, the newly stored instance is removed
     * from Orthanc again and the upload is rejected, so studies are never mixed across patients.
     */
    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public UploadResult upload(Long patientId, List<MultipartFile> files, Long encounterId, Long orderId) {
        Patient patient = access.require(patientId, Access.CLINICAL_WRITE);
        if (files == null || files.isEmpty()) {
            throw new BusinessRuleException("At least one DICOM file is required");
        }
        if (files.size() > MAX_FILES_PER_UPLOAD) {
            throw new BusinessRuleException("At most " + MAX_FILES_PER_UPLOAD + " files per upload");
        }
        validateLinks(patientId, encounterId, orderId);

        Set<String> orthancStudyIds = new LinkedHashSet<>();
        int stored = 0;
        int already = 0;
        for (MultipartFile f : files) {
            byte[] bytes;
            try {
                bytes = f.getBytes();
            } catch (IOException ex) {
                throw new BusinessRuleException("Could not read uploaded file " + f.getOriginalFilename());
            }
            StoredInstance s = archive.storeInstance(bytes);
            var existing = studies.findByOrthancStudyId(s.studyId());
            if (existing.isPresent() && !existing.get().getPatientId().equals(patientId)) {
                if (!s.alreadyStored()) {
                    archive.deleteInstance(s.instanceId());
                }
                audit.record("UPLOAD", "ImagingStudy", existing.get().getId(), patientId, Outcome.DENIED,
                        "study belongs to another patient");
                throw new ConflictException("A file belongs to a study that is linked to a different patient");
            }
            orthancStudyIds.add(s.studyId());
            if (s.alreadyStored()) already++; else stored++;
        }

        List<String> warnings = new ArrayList<>();
        List<StudySummary> result = new ArrayList<>();
        for (String orthancId : orthancStudyIds) {
            ImagingStudy study = linkOrthancStudy(patient, orthancId, ImagingStudy.Source.UPLOAD, encounterId, orderId);
            if (study.isPatientIdMismatch()) {
                warnings.add("Study " + study.getStudyInstanceUid() + ": DICOM PatientID '" + study.getDicomPatientId()
                        + "' differs from MRN " + patient.getMrn() + ". Verify the association.");
            }
            result.add(toSummary(study, 0));
        }
        audit.record("UPLOAD", "ImagingStudy", null, patientId, Outcome.SUCCESS,
                "files=" + files.size() + " studies=" + orthancStudyIds.size());
        return new UploadResult(result, stored, already, warnings);
    }

    /** Links a study that already exists in Orthanc (e.g. sent by a modality via C-STORE). */
    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public StudyResponse linkExisting(Long patientId, LinkStudyRequest req) {
        Patient patient = access.require(patientId, Access.CLINICAL_WRITE);
        validateLinks(patientId, req.encounterId(), req.orderId());
        var linked = studies.findByStudyInstanceUid(req.studyInstanceUid());
        if (linked.isPresent() && !linked.get().getPatientId().equals(patientId)) {
            throw new ConflictException("This study is already linked to a different patient");
        }
        String orthancId = archive.findStudyByInstanceUid(req.studyInstanceUid())
                .orElseThrow(() -> new NotFoundException("Study " + req.studyInstanceUid() + " is not in the imaging archive"));
        ImagingStudy study = linkOrthancStudy(patient, orthancId, ImagingStudy.Source.LINKED, req.encounterId(), req.orderId());
        audit.success("LINK", "ImagingStudy", study.getId(), patientId);
        return toStudyResponse(study);
    }

    @Transactional(readOnly = true)
    public List<StudySummary> listStudies(Long patientId) {
        access.require(patientId, Access.CLINICAL_READ);
        List<ImagingStudy> list = studies.findByPatientIdOrderByStudyDateDescIdDesc(patientId);
        audit.success("LIST", "ImagingStudy", null, patientId);
        return list.stream().map(s -> toSummary(s, countValidFindings(s.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public StudyResponse getStudy(Long studyId) {
        ImagingStudy s = requireStudy(studyId, Access.CLINICAL_READ);
        audit.success("READ", "ImagingStudy", studyId, s.getPatientId());
        return toStudyResponse(s);
    }

    /** Loads a study after verifying the caller's access to its patient. */
    @Transactional(readOnly = true)
    public ImagingStudy requireStudy(Long studyId, Access level) {
        ImagingStudy s = studies.findById(studyId).orElseThrow(() -> NotFoundException.of("Imaging study", studyId));
        access.require(s.getPatientId(), level);
        return s;
    }

    // ---- Findings ----

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public FindingResponse addFinding(Long studyId, FindingRequest req) {
        ImagingStudy s = requireStudy(studyId, Access.CLINICAL_WRITE);
        if (req.seriesId() != null) {
            ImagingSeries ser = series.findById(req.seriesId())
                    .orElseThrow(() -> NotFoundException.of("Series", req.seriesId()));
            if (!ser.getStudyId().equals(studyId)) {
                throw new BusinessRuleException("Series " + req.seriesId() + " does not belong to study " + studyId);
            }
        }
        if (req.source() == ImagingFinding.Source.AI_UNVALIDATED) {
            // AI findings may only arrive from a separately documented inference service, never by hand.
            throw new BusinessRuleException("AI-sourced findings cannot be entered manually");
        }
        if (req.status() == ImagingFinding.Status.ENTERED_IN_ERROR) {
            throw new BusinessRuleException("New findings must be PRELIMINARY or FINAL");
        }
        ImagingFinding f = findings.save(new ImagingFinding(studyId, req.seriesId(), req.type(),
                req.laterality() == null ? ImagingFinding.Laterality.NOT_APPLICABLE : req.laterality(),
                trim(req.location()), req.sizeMm(), req.description().trim(), req.source(),
                req.status() == null ? ImagingFinding.Status.PRELIMINARY : req.status(), trim(req.sopInstanceUid()),
                req.frameNumber(), CurrentUser.require().id()));
        audit.record("CREATE", "ImagingFinding", f.getId(), s.getPatientId(), Outcome.SUCCESS, "type=" + req.type());
        return toResponse(f, names(List.of(f.getRecordedBy())));
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public FindingResponse finalizeFinding(Long findingId) {
        ImagingFinding f = findings.findById(findingId).orElseThrow(() -> NotFoundException.of("Finding", findingId));
        ImagingStudy s = requireStudy(f.getStudyId(), Access.CLINICAL_WRITE);
        f.finalizeFinding();
        audit.success("FINALIZE", "ImagingFinding", findingId, s.getPatientId());
        return toResponse(f, names(List.of(f.getRecordedBy())));
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public FindingResponse findingInError(Long findingId, String reason) {
        ImagingFinding f = findings.findById(findingId).orElseThrow(() -> NotFoundException.of("Finding", findingId));
        ImagingStudy s = requireStudy(f.getStudyId(), Access.CLINICAL_WRITE);
        f.markEnteredInError(reason.trim());
        audit.success("ENTERED_IN_ERROR", "ImagingFinding", findingId, s.getPatientId());
        return toResponse(f, names(List.of(f.getRecordedBy())));
    }

    // ---- helpers ----

    private ImagingStudy linkOrthancStudy(Patient patient, String orthancStudyId, ImagingStudy.Source source,
                                          Long encounterId, Long orderId) {
        StudyMetadata meta = archive.studyMetadata(orthancStudyId);
        if (meta.studyInstanceUid() == null) {
            throw new BusinessRuleException("Study has no StudyInstanceUID");
        }
        ImagingStudy study = studies.findByOrthancStudyId(orthancStudyId).orElseGet(() ->
                studies.save(new ImagingStudy(patient.getId(), meta.studyInstanceUid(), orthancStudyId, source,
                        CurrentUser.find().map(AuthenticatedUser::id).orElse(null))));
        String modalities = meta.series().stream().map(ImagingArchive.SeriesMetadata::modality)
                .filter(Objects::nonNull).distinct().sorted().collect(Collectors.joining(","));
        int instances = meta.series().stream().mapToInt(ImagingArchive.SeriesMetadata::instances).sum();
        boolean mismatch = meta.dicomPatientId() != null && !meta.dicomPatientId().equalsIgnoreCase(patient.getMrn());
        study.updateMetadata(meta.accessionNumber(), meta.studyDate(), meta.description(), modalities,
                meta.series().size(), instances, meta.dicomPatientId(), mismatch);
        study.link(encounterId, orderId);
        for (var sm : meta.series()) {
            if (sm.seriesInstanceUid() == null) {
                continue;
            }
            ImagingSeries s = series.findBySeriesInstanceUid(sm.seriesInstanceUid())
                    .orElseGet(() -> series.save(new ImagingSeries(study.getId(), sm.seriesInstanceUid(), sm.orthancSeriesId())));
            s.update(sm.modality(), sm.bodyPart(), sm.description(), sm.seriesNumber(), sm.instances());
        }
        if (orderId != null) {
            orders.findById(orderId).ifPresent(ImagingOrder::complete);
        }
        return study;
    }

    private void validateLinks(Long patientId, Long encounterId, Long orderId) {
        if (encounterId != null) {
            encounters.requireEditableForPatient(encounterId, patientId);
        }
        if (orderId != null) {
            ImagingOrder o = orders.findById(orderId).orElseThrow(() -> NotFoundException.of("Imaging order", orderId));
            if (!o.getPatientId().equals(patientId)) {
                throw new BusinessRuleException("Imaging order " + orderId + " does not belong to this patient");
            }
            if (o.getStatus() == ImagingOrder.Status.CANCELLED) {
                throw new ConflictException("Imaging order " + orderId + " is cancelled");
            }
        }
    }

    private int countValidFindings(Long studyId) {
        return (int) visibleFindings(studyId).stream()
                .filter(f -> f.getStatus() != ImagingFinding.Status.ENTERED_IN_ERROR).count();
    }

    /** Patients see only finalized findings; staff see all, including corrections. */
    private List<ImagingFinding> visibleFindings(Long studyId) {
        List<ImagingFinding> all = findings.findByStudyIdOrderByRecordedAtDesc(studyId);
        boolean patient = CurrentUser.find().map(u -> u.is(com.rdss.common.security.Role.PATIENT)).orElse(false);
        return patient ? all.stream().filter(f -> f.getStatus() == ImagingFinding.Status.FINAL).toList() : all;
    }

    private StudyResponse toStudyResponse(ImagingStudy s) {
        List<ImagingFinding> fs = visibleFindings(s.getId());
        Map<Long, AppUser> byId = names(fs.stream().map(ImagingFinding::getRecordedBy).toList());
        List<SeriesResponse> seriesResponses = series.findByStudyIdOrderBySeriesNumberAsc(s.getId()).stream()
                .map(x -> new SeriesResponse(x.getId(), x.getSeriesInstanceUid(), x.getModality(), x.getBodyPart(),
                        x.getSeriesDescription(), x.getSeriesNumber(), x.getNumberOfInstances()))
                .toList();
        int valid = (int) fs.stream().filter(f -> f.getStatus() != ImagingFinding.Status.ENTERED_IN_ERROR).count();
        return new StudyResponse(toSummary(s, valid), s.getDicomPatientId(), seriesResponses,
                fs.stream().map(f -> toResponse(f, byId)).toList());
    }

    static StudySummary toSummary(ImagingStudy s, int findingCount) {
        return new StudySummary(s.getId(), s.getPatientId(), s.getEncounterId(), s.getOrderId(), s.getStudyInstanceUid(),
                s.getAccessionNumber(), s.getStudyDate(), s.getStudyDescription(), s.getModalities(),
                s.getNumberOfSeries(), s.getNumberOfInstances(), s.isPatientIdMismatch(), s.getSource(),
                s.getLinkedAt(), findingCount);
    }

    private Map<Long, AppUser> names(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        return users.findAllById(distinct).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private static String nameOf(Map<Long, AppUser> byId, Long id) {
        AppUser u = id == null ? null : byId.get(id);
        return u == null ? null : u.getFullName();
    }

    private static OrderResponse toResponse(ImagingOrder o, Map<Long, AppUser> byId) {
        return new OrderResponse(o.getId(), o.getPatientId(), o.getEncounterId(), o.getAccessionNumber(), o.getModality(),
                o.getBodyPart(), o.getProcedureDescription(), o.getClinicalIndication(), o.getPriority(), o.getStatus(),
                o.getExternalOrderId(), o.getOrderedBy(), nameOf(byId, o.getOrderedBy()), o.getOrderedAt());
    }

    private static FindingResponse toResponse(ImagingFinding f, Map<Long, AppUser> byId) {
        return new FindingResponse(f.getId(), f.getStudyId(), f.getSeriesId(), f.getType(), f.getLaterality(),
                f.getLocation(), f.getSizeMm(), f.getDescription(), f.getSource(), f.getStatus(), f.getSopInstanceUid(),
                f.getFrameNumber(), f.getErrorReason(), f.getRecordedBy(), nameOf(byId, f.getRecordedBy()),
                f.getRecordedAt());
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
