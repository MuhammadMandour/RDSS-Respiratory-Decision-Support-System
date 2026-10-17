package com.rdss.encounters;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.PageResponse;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.ConflictException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;
import com.rdss.common.security.Role;
import com.rdss.encounters.EncounterDtos.CreateEncounterRequest;
import com.rdss.encounters.EncounterDtos.EncounterResponse;
import com.rdss.encounters.EncounterDtos.EncounterSummary;
import com.rdss.encounters.EncounterDtos.UpdateEncounterRequest;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientAccessPolicy;
import com.rdss.patients.PatientAccessPolicy.Access;
import com.rdss.patients.PatientRepository;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clinical encounter documentation. Finalized encounters are immutable; corrections are made
 * through an amendment, which supersedes the original once it is itself finalized.
 */
@Service
public class EncounterService {

    private static final EnumSet<ClinicalEncounter.Status> PATIENT_VISIBLE =
            EnumSet.of(ClinicalEncounter.Status.FINALIZED, ClinicalEncounter.Status.SUPERSEDED);

    private final ClinicalEncounterRepository encounters;
    private final PatientRepository patients;
    private final AppUserRepository users;
    private final PatientAccessPolicy access;
    private final AuditService audit;

    public EncounterService(ClinicalEncounterRepository encounters, PatientRepository patients,
                            AppUserRepository users, PatientAccessPolicy access, AuditService audit) {
        this.encounters = encounters;
        this.patients = patients;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<EncounterSummary> listForPatient(Long patientId, Pageable pageable) {
        Patient patient = access.require(patientId, Access.CLINICAL_READ);
        AuthenticatedUser user = CurrentUser.require();
        // Patients only see signed-off documentation, never drafts.
        Page<ClinicalEncounter> page = user.is(Role.PATIENT)
                ? encounters.findByPatientIdAndStatusIn(patientId, PATIENT_VISIBLE, pageable)
                : encounters.findByPatientId(patientId, pageable);
        Map<Long, AppUser> clinicians = clinicians(page.getContent());
        audit.success("LIST", "ClinicalEncounter", null, patientId);
        return PageResponse.of(page, e -> toSummary(e, patient.fullName(), clinicians));
    }

    /** The current clinician's own encounters with the given status (e.g. open drafts). */
    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional(readOnly = true)
    public PageResponse<EncounterSummary> listMine(ClinicalEncounter.Status status, Pageable pageable) {
        AuthenticatedUser user = CurrentUser.require();
        Page<ClinicalEncounter> page = encounters.findByClinicianIdAndStatus(user.id(), status, pageable);
        Map<Long, String> names = patients.findAllById(page.getContent().stream()
                        .map(ClinicalEncounter::getPatientId).distinct().toList()).stream()
                .collect(Collectors.toMap(Patient::getId, Patient::fullName));
        Map<Long, AppUser> clinicians = clinicians(page.getContent());
        return PageResponse.of(page, e -> toSummary(e, names.get(e.getPatientId()), clinicians));
    }

    @Transactional(readOnly = true)
    public EncounterResponse get(Long id) {
        ClinicalEncounter e = requireReadable(id);
        audit.success("READ", "ClinicalEncounter", id, e.getPatientId());
        return toResponse(e);
    }

    /** Loads an encounter after verifying the caller may read it (used by other modules too). */
    @Transactional(readOnly = true)
    public ClinicalEncounter requireReadable(Long id) {
        ClinicalEncounter e = encounters.findById(id).orElseThrow(() -> NotFoundException.of("Encounter", id));
        access.require(e.getPatientId(), Access.CLINICAL_READ);
        if (CurrentUser.require().is(Role.PATIENT) && !PATIENT_VISIBLE.contains(e.getStatus())) {
            throw NotFoundException.of("Encounter", id);
        }
        return e;
    }

    /** Verifies the encounter belongs to the patient and is still editable (for linked records). */
    @Transactional(readOnly = true)
    public ClinicalEncounter requireEditableForPatient(Long encounterId, Long patientId) {
        ClinicalEncounter e = encounters.findById(encounterId)
                .orElseThrow(() -> NotFoundException.of("Encounter", encounterId));
        if (!e.getPatientId().equals(patientId)) {
            throw new BusinessRuleException("Encounter " + encounterId + " does not belong to this patient");
        }
        e.requireEditable();
        return e;
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public EncounterResponse create(Long patientId, CreateEncounterRequest req) {
        access.require(patientId, Access.CLINICAL_WRITE);
        AuthenticatedUser user = CurrentUser.require();
        var e = new ClinicalEncounter(patientId, user.id(), req.type(),
                req.startedAt() == null ? Instant.now() : req.startedAt());
        e.setChiefComplaint(blankToNull(req.chiefComplaint()));
        e = encounters.save(e);
        audit.success("CREATE", "ClinicalEncounter", e.getId(), patientId);
        return toResponse(e);
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public EncounterResponse update(Long id, UpdateEncounterRequest req) {
        ClinicalEncounter e = encounters.findById(id).orElseThrow(() -> NotFoundException.of("Encounter", id));
        access.require(e.getPatientId(), Access.CLINICAL_WRITE);
        e.requireEditable();
        if (req.version() != e.getVersion()) {
            throw new ConflictException("The encounter was modified by someone else; reload and retry");
        }
        e.setType(req.type());
        e.setChiefComplaint(blankToNull(req.chiefComplaint()));
        e.setHistoryOfPresentIllness(blankToNull(req.historyOfPresentIllness()));
        e.setCough(req.cough());
        e.setCoughDurationDays(req.coughDurationDays());
        e.setSputum(req.sputum());
        e.setSputumDescription(blankToNull(req.sputumDescription()));
        e.setWheezing(req.wheezing());
        e.setDyspnea(req.dyspnea());
        e.setMmrcGrade(req.mmrcGrade());
        e.setCatScore(req.catScore());
        e.setChestPain(req.chestPain());
        e.setFever(req.fever());
        e.setHemoptysis(req.hemoptysis());
        e.setSymptomDurationDays(req.symptomDurationDays());
        e.setPhysicalExam(blankToNull(req.physicalExam()));
        e.setLabResults(blankToNull(req.labResults()));
        e.setAssessmentNotes(blankToNull(req.assessmentNotes()));
        e.setPlanNotes(blankToNull(req.planNotes()));
        e.setFollowUp(blankToNull(req.followUp()));
        encounters.saveAndFlush(e);
        audit.success("UPDATE", "ClinicalEncounter", id, e.getPatientId());
        return toResponse(e);
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public EncounterResponse finalizeEncounter(Long id) {
        ClinicalEncounter e = encounters.findById(id).orElseThrow(() -> NotFoundException.of("Encounter", id));
        access.require(e.getPatientId(), Access.CLINICAL_WRITE);
        if (e.getChiefComplaint() == null) {
            throw new BusinessRuleException("A chief complaint is required before finalizing");
        }
        e.finalizeBy(CurrentUser.require().id(), Instant.now());
        if (e.getAmendsEncounterId() != null) {
            encounters.findById(e.getAmendsEncounterId()).ifPresent(ClinicalEncounter::supersede);
        }
        audit.success("FINALIZE", "ClinicalEncounter", id, e.getPatientId());
        return toResponse(e);
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public EncounterResponse amend(Long id, String reason) {
        ClinicalEncounter original = encounters.findById(id).orElseThrow(() -> NotFoundException.of("Encounter", id));
        access.require(original.getPatientId(), Access.CLINICAL_WRITE);
        if (original.getStatus() != ClinicalEncounter.Status.FINALIZED) {
            throw new ConflictException("Only the current finalized version of an encounter can be amended");
        }
        if (encounters.existsByAmendsEncounterId(id)) {
            throw new ConflictException("An amendment for this encounter already exists");
        }
        ClinicalEncounter amendment = encounters.save(original.amendment(CurrentUser.require().id(), reason.trim()));
        audit.record("AMEND", "ClinicalEncounter", amendment.getId(), original.getPatientId(), Outcome.SUCCESS,
                "amends=" + id);
        return toResponse(amendment);
    }

    private Map<Long, AppUser> clinicians(List<ClinicalEncounter> list) {
        var ids = list.stream().map(ClinicalEncounter::getClinicianId).distinct().toList();
        return users.findAllById(ids).stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private EncounterSummary toSummary(ClinicalEncounter e, String patientName, Map<Long, AppUser> clinicians) {
        AppUser c = clinicians.get(e.getClinicianId());
        return new EncounterSummary(e.getId(), e.getPatientId(), patientName, e.getClinicianId(),
                c == null ? null : c.getFullName(), e.getType(), e.getStatus(), e.getStartedAt(), e.getFinalizedAt(),
                e.getChiefComplaint(), e.getAmendsEncounterId());
    }

    EncounterResponse toResponse(ClinicalEncounter e) {
        String clinicianName = users.findById(e.getClinicianId()).map(AppUser::getFullName).orElse(null);
        Long amendedBy = e.getStatus() == ClinicalEncounter.Status.IN_PROGRESS ? null
                : encounters.findByAmendsEncounterId(e.getId()).map(ClinicalEncounter::getId).orElse(null);
        return new EncounterResponse(e.getId(), e.getPatientId(), e.getClinicianId(), clinicianName, e.getType(),
                e.getStatus(), e.getStartedAt(), e.getFinalizedAt(), e.getFinalizedBy(), e.getAmendsEncounterId(),
                e.getAmendmentReason(), amendedBy, e.getChiefComplaint(), e.getHistoryOfPresentIllness(),
                e.getCough(), e.getCoughDurationDays(), e.getSputum(), e.getSputumDescription(), e.getWheezing(),
                e.getDyspnea(), e.getMmrcGrade(), e.getCatScore(), e.getChestPain(), e.getFever(), e.getHemoptysis(),
                e.getSymptomDurationDays(), e.getPhysicalExam(), e.getLabResults(), e.getAssessmentNotes(),
                e.getPlanNotes(), e.getFollowUp(), e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
