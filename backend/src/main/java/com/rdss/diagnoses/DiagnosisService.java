package com.rdss.diagnoses;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.CurrentUser;
import com.rdss.diagnoses.DiagnosisDtos.DiagnosisRequest;
import com.rdss.diagnoses.DiagnosisDtos.DiagnosisResponse;
import com.rdss.encounters.ClinicalEncounter;
import com.rdss.encounters.ClinicalEncounterRepository;
import com.rdss.patients.PatientAccessPolicy;
import com.rdss.patients.PatientAccessPolicy.Access;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coded diagnoses on the patient record, optionally linked to an encounter. */
@Service
public class DiagnosisService {

    private final DiagnosisRepository diagnoses;
    private final Icd10Service icd10;
    private final ClinicalEncounterRepository encounters;
    private final AppUserRepository users;
    private final PatientAccessPolicy access;
    private final AuditService audit;

    public DiagnosisService(DiagnosisRepository diagnoses, Icd10Service icd10, ClinicalEncounterRepository encounters,
                            AppUserRepository users, PatientAccessPolicy access, AuditService audit) {
        this.diagnoses = diagnoses;
        this.icd10 = icd10;
        this.encounters = encounters;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DiagnosisResponse> list(Long patientId) {
        access.require(patientId, Access.CLINICAL_READ);
        List<Diagnosis> list = diagnoses.findByPatientIdOrderByRecordedAtDesc(patientId);
        Map<Long, AppUser> byId = names(list.stream().map(Diagnosis::getRecordedBy).toList());
        audit.success("LIST", "Diagnosis", null, patientId);
        return list.stream().map(d -> toResponse(d, byId)).toList();
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public DiagnosisResponse record(Long patientId, DiagnosisRequest req) {
        access.require(patientId, Access.CLINICAL_WRITE);
        if (req.encounterId() != null) {
            ClinicalEncounter e = encounters.findById(req.encounterId())
                    .orElseThrow(() -> NotFoundException.of("Encounter", req.encounterId()));
            if (!e.getPatientId().equals(patientId)) {
                throw new BusinessRuleException("Encounter " + req.encounterId() + " does not belong to this patient");
            }
            // Diagnoses may be coded on finalized encounters (coding often follows sign-off), but never superseded ones.
            if (e.getStatus() == ClinicalEncounter.Status.SUPERSEDED) {
                throw new BusinessRuleException("Encounter " + req.encounterId() + " has been superseded by an amendment");
            }
        }
        Icd10Code code = icd10.requireUsable(req.code());
        Diagnosis d = diagnoses.save(new Diagnosis(patientId, req.encounterId(), code, req.type(), req.onsetDate(),
                trim(req.notes()), CurrentUser.require().id()));
        audit.record("CREATE", "Diagnosis", d.getId(), patientId, Outcome.SUCCESS, "code=" + d.getIcd10Code());
        return toResponse(d, names(List.of(d.getRecordedBy())));
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public DiagnosisResponse resolve(Long id) {
        Diagnosis d = diagnoses.findById(id).orElseThrow(() -> NotFoundException.of("Diagnosis", id));
        access.require(d.getPatientId(), Access.CLINICAL_WRITE);
        d.resolve(LocalDate.now());
        audit.success("RESOLVE", "Diagnosis", id, d.getPatientId());
        return toResponse(d, names(List.of(d.getRecordedBy())));
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public DiagnosisResponse enteredInError(Long id, String reason) {
        Diagnosis d = diagnoses.findById(id).orElseThrow(() -> NotFoundException.of("Diagnosis", id));
        access.require(d.getPatientId(), Access.CLINICAL_WRITE);
        d.markEnteredInError(reason.trim());
        audit.success("ENTERED_IN_ERROR", "Diagnosis", id, d.getPatientId());
        return toResponse(d, names(List.of(d.getRecordedBy())));
    }

    private Map<Long, AppUser> names(List<Long> ids) {
        return users.findAllById(ids.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private static DiagnosisResponse toResponse(Diagnosis d, Map<Long, AppUser> byId) {
        AppUser u = byId.get(d.getRecordedBy());
        return new DiagnosisResponse(d.getId(), d.getPatientId(), d.getEncounterId(), d.getIcd10Code(),
                d.getCodeVersion(), d.getDescription(), d.getType(), d.getStatus(), d.getOnsetDate(),
                d.getResolvedDate(), d.getNotes(), d.getErrorReason(), d.getRecordedBy(),
                u == null ? null : u.getFullName(), d.getRecordedAt());
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
