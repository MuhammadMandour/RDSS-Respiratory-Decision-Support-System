package com.rdss.encounters;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.ConflictException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.CurrentUser;
import com.rdss.encounters.TreatmentDtos.PrescriptionRequest;
import com.rdss.encounters.TreatmentDtos.PrescriptionResponse;
import com.rdss.encounters.TreatmentDtos.TreatmentPlanRequest;
import com.rdss.encounters.TreatmentDtos.TreatmentPlanResponse;
import com.rdss.patients.PatientAccessPolicy;
import com.rdss.patients.PatientAccessPolicy.Access;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Treatment plans and prescriptions. Patients may read their own; clinicians on the care team write. */
@Service
public class TreatmentService {

    private final TreatmentPlanRepository plans;
    private final PrescriptionRepository prescriptions;
    private final EncounterService encounterService;
    private final AppUserRepository users;
    private final PatientAccessPolicy access;
    private final AuditService audit;

    public TreatmentService(TreatmentPlanRepository plans, PrescriptionRepository prescriptions,
                            EncounterService encounterService, AppUserRepository users, PatientAccessPolicy access,
                            AuditService audit) {
        this.plans = plans;
        this.prescriptions = prescriptions;
        this.encounterService = encounterService;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    // ---- Treatment plans ----

    @Transactional(readOnly = true)
    public List<TreatmentPlanResponse> listPlans(Long patientId) {
        access.require(patientId, Access.CLINICAL_READ);
        List<TreatmentPlan> list = plans.findByPatientIdOrderByStartDateDescIdDesc(patientId);
        Map<Long, AppUser> byId = names(list.stream().map(TreatmentPlan::getCreatedBy));
        audit.success("LIST", "TreatmentPlan", null, patientId);
        return list.stream().map(p -> toResponse(p, byId)).toList();
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public TreatmentPlanResponse createPlan(Long patientId, TreatmentPlanRequest req) {
        access.require(patientId, Access.CLINICAL_WRITE);
        validateDates(req);
        if (req.encounterId() != null) {
            encounterService.requireEditableForPatient(req.encounterId(), patientId);
        }
        var plan = new TreatmentPlan(patientId, req.encounterId(), req.title().trim(), req.startDate(),
                CurrentUser.require().id());
        apply(plan, req);
        plan = plans.save(plan);
        audit.success("CREATE", "TreatmentPlan", plan.getId(), patientId);
        return toResponse(plan, names(Stream.of(plan.getCreatedBy())));
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public TreatmentPlanResponse updatePlan(Long planId, TreatmentPlanRequest req) {
        TreatmentPlan plan = plans.findById(planId).orElseThrow(() -> NotFoundException.of("Treatment plan", planId));
        access.require(plan.getPatientId(), Access.CLINICAL_WRITE);
        if (req.version() == null || req.version() != plan.getVersion()) {
            throw new ConflictException("The treatment plan was modified by someone else; reload and retry");
        }
        if (plan.getStatus() != TreatmentPlan.Status.ACTIVE) {
            throw new ConflictException("Only active treatment plans can be changed");
        }
        validateDates(req);
        plan.setTitle(req.title().trim());
        plan.setStartDate(req.startDate());
        apply(plan, req);
        plans.saveAndFlush(plan);
        audit.record("UPDATE", "TreatmentPlan", planId, plan.getPatientId(), Outcome.SUCCESS,
                "status=" + plan.getStatus());
        return toResponse(plan, names(Stream.of(plan.getCreatedBy())));
    }

    // ---- Prescriptions ----

    @Transactional(readOnly = true)
    public List<PrescriptionResponse> listPrescriptions(Long patientId) {
        access.require(patientId, Access.CLINICAL_READ);
        List<Prescription> list = prescriptions.findByPatientIdOrderByPrescribedAtDesc(patientId);
        Map<Long, AppUser> byId = names(list.stream().map(Prescription::getPrescribedBy));
        audit.success("LIST", "Prescription", null, patientId);
        return list.stream().map(p -> toResponse(p, byId)).toList();
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public PrescriptionResponse prescribe(Long patientId, PrescriptionRequest req) {
        access.require(patientId, Access.CLINICAL_WRITE);
        if (req.encounterId() != null) {
            encounterService.requireEditableForPatient(req.encounterId(), patientId);
        }
        var p = prescriptions.save(new Prescription(patientId, req.encounterId(), req.medicationName().trim(),
                req.dose().trim(), req.route().trim(), req.frequency().trim(), trim(req.duration()),
                trim(req.quantity()), req.refills(), trim(req.instructions()), CurrentUser.require().id()));
        audit.success("CREATE", "Prescription", p.getId(), patientId);
        return toResponse(p, names(Stream.of(p.getPrescribedBy())));
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public PrescriptionResponse discontinue(Long id, String reason) {
        Prescription p = prescriptions.findById(id).orElseThrow(() -> NotFoundException.of("Prescription", id));
        access.require(p.getPatientId(), Access.CLINICAL_WRITE);
        p.discontinue(reason.trim());
        audit.success("DISCONTINUE", "Prescription", id, p.getPatientId());
        return toResponse(p, names(Stream.of(p.getPrescribedBy())));
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public PrescriptionResponse complete(Long id) {
        Prescription p = prescriptions.findById(id).orElseThrow(() -> NotFoundException.of("Prescription", id));
        access.require(p.getPatientId(), Access.CLINICAL_WRITE);
        p.complete();
        audit.success("COMPLETE", "Prescription", id, p.getPatientId());
        return toResponse(p, names(Stream.of(p.getPrescribedBy())));
    }

    private static void validateDates(TreatmentPlanRequest req) {
        if (req.reviewDate() != null && req.reviewDate().isBefore(req.startDate())) {
            throw new BusinessRuleException("Review date cannot be before the start date");
        }
    }

    private static void apply(TreatmentPlan plan, TreatmentPlanRequest req) {
        plan.setGoals(trim(req.goals()));
        plan.setInterventions(trim(req.interventions()));
        plan.setFollowUpInstructions(trim(req.followUpInstructions()));
        plan.setReviewDate(req.reviewDate());
        if (req.status() != null) {
            plan.setStatus(req.status());
        }
    }

    private Map<Long, AppUser> names(Stream<Long> ids) {
        return users.findAllById(ids.distinct().toList()).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private static String nameOf(Map<Long, AppUser> byId, Long id) {
        AppUser u = byId.get(id);
        return u == null ? null : u.getFullName();
    }

    private static TreatmentPlanResponse toResponse(TreatmentPlan p, Map<Long, AppUser> byId) {
        return new TreatmentPlanResponse(p.getId(), p.getPatientId(), p.getEncounterId(), p.getTitle(), p.getGoals(),
                p.getInterventions(), p.getFollowUpInstructions(), p.getStartDate(), p.getReviewDate(), p.getStatus(),
                p.getCreatedBy(), nameOf(byId, p.getCreatedBy()), p.getCreatedAt(), p.getUpdatedAt(), p.getVersion());
    }

    private static PrescriptionResponse toResponse(Prescription p, Map<Long, AppUser> byId) {
        return new PrescriptionResponse(p.getId(), p.getPatientId(), p.getEncounterId(), p.getMedicationName(),
                p.getDose(), p.getRoute(), p.getFrequency(), p.getDuration(), p.getQuantity(), p.getRefills(),
                p.getInstructions(), p.getStatus(), p.getDiscontinuedReason(), p.getPrescribedBy(),
                nameOf(byId, p.getPrescribedBy()), p.getPrescribedAt());
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
