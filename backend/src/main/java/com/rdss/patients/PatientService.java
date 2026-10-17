package com.rdss.patients;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rdss.audit.AuditService;
import com.rdss.common.PageResponse;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;
import com.rdss.common.security.Role;
import com.rdss.patients.PatientAccessPolicy.Access;
import com.rdss.patients.PatientDtos.CareTeamMemberResponse;
import com.rdss.patients.PatientDtos.ClinicianRef;
import com.rdss.patients.PatientDtos.PatientContactUpdateRequest;
import com.rdss.patients.PatientDtos.PatientRequest;
import com.rdss.patients.PatientDtos.PatientResponse;
import com.rdss.patients.PatientDtos.PatientSummary;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientService {

    private final PatientRepository patients;
    private final PatientCareTeamRepository careTeam;
    private final AppUserRepository users;
    private final PatientAccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public PatientService(PatientRepository patients, PatientCareTeamRepository careTeam, AppUserRepository users,
                          PatientAccessPolicy access, AuditService audit, ApplicationEventPublisher events) {
        this.patients = patients;
        this.careTeam = careTeam;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.events = events;
    }

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional(readOnly = true)
    public PageResponse<PatientSummary> search(String query, boolean includeInactive, Pageable pageable) {
        AuthenticatedUser user = CurrentUser.require();
        String like = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        Page<Patient> page = user.is(Role.ADMIN)
                ? patients.searchAll(like, includeInactive, pageable)
                : patients.searchForClinician(user.id(), like, includeInactive, pageable);
        Map<Long, AppUser> clinicians = loadUsers(page.getContent().stream()
                .map(Patient::getPrimaryClinicianId).filter(Objects::nonNull).toList());
        audit.success("SEARCH", "Patient", null, null);
        return PageResponse.of(page, p -> toSummary(p, clinicians));
    }

    @Transactional(readOnly = true)
    public PatientResponse get(Long id) {
        Patient p = access.require(id, Access.DEMOGRAPHICS_READ);
        audit.success("READ", "Patient", id, id);
        return toResponse(p, includeClinical(CurrentUser.require()));
    }

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional
    public PatientResponse create(PatientRequest req) {
        AuthenticatedUser user = CurrentUser.require();
        validatePrimaryClinician(req.primaryClinicianId());
        Patient p = new Patient(nextMrn(), req.firstName().trim(), req.lastName().trim(), req.dateOfBirth(), req.sex());
        applyDemographics(p, req);
        applyClinical(p, req);
        p.setPrimaryClinicianId(req.primaryClinicianId());
        p = patients.save(p);
        if (user.is(Role.CLINICIAN)) {
            addToCareTeam(p.getId(), user.id(), PatientCareTeamMember.Reason.REGISTERED);
        }
        if (req.primaryClinicianId() != null) {
            addToCareTeam(p.getId(), req.primaryClinicianId(), PatientCareTeamMember.Reason.PRIMARY);
        }
        audit.success("CREATE", "Patient", p.getId(), p.getId());
        events.publishEvent(new PatientChangedEvent(p.getId(), true));
        return toResponse(p, includeClinical(user));
    }

    /** Creates the patient record for a self-registered portal account. */
    @Transactional
    public Patient createSelfRegistered(AppUser account, String firstName, String lastName, LocalDate dob,
                                        Patient.Sex sex, String phone) {
        Patient p = new Patient(nextMrn(), firstName.trim(), lastName.trim(), dob, sex);
        p.setEmail(account.getEmail());
        p.setPhone(phone);
        p.setUserId(account.getId());
        return patients.save(p);
    }

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional
    public PatientResponse update(Long id, PatientRequest req) {
        AuthenticatedUser user = CurrentUser.require();
        Patient p = access.require(id, user.is(Role.CLINICIAN) ? Access.CLINICAL_WRITE : Access.DEMOGRAPHICS_WRITE);
        p.setFirstName(req.firstName().trim());
        p.setLastName(req.lastName().trim());
        p.setDateOfBirth(req.dateOfBirth());
        p.setSex(req.sex());
        applyDemographics(p, req);
        // Administrators maintain demographics only; clinical fields are left untouched.
        if (user.is(Role.CLINICIAN)) {
            applyClinical(p, req);
        }
        if (!Objects.equals(p.getPrimaryClinicianId(), req.primaryClinicianId())) {
            validatePrimaryClinician(req.primaryClinicianId());
            p.setPrimaryClinicianId(req.primaryClinicianId());
            if (req.primaryClinicianId() != null) {
                addToCareTeam(p.getId(), req.primaryClinicianId(), PatientCareTeamMember.Reason.PRIMARY);
            }
        }
        audit.success("UPDATE", "Patient", id, id);
        events.publishEvent(new PatientChangedEvent(id, false));
        return toResponse(p, includeClinical(user));
    }

    @Transactional
    public PatientResponse updateContact(Long id, PatientContactUpdateRequest req) {
        Patient p = access.require(id, Access.DEMOGRAPHICS_WRITE);
        p.setPhone(blankToNull(req.phone()));
        p.setEmail(blankToNull(req.email()));
        p.setAddressLine(blankToNull(req.addressLine()));
        p.setCity(blankToNull(req.city()));
        p.setPostalCode(blankToNull(req.postalCode()));
        p.setCountry(blankToNull(req.country()));
        p.setEmergencyContactName(blankToNull(req.emergencyContactName()));
        p.setEmergencyContactPhone(blankToNull(req.emergencyContactPhone()));
        p.setEmergencyContactRelation(blankToNull(req.emergencyContactRelation()));
        audit.success("UPDATE_CONTACT", "Patient", id, id);
        events.publishEvent(new PatientChangedEvent(id, false));
        return toResponse(p, includeClinical(CurrentUser.require()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public PatientResponse setActive(Long id, boolean active) {
        Patient p = access.require(id, Access.DEMOGRAPHICS_WRITE);
        p.setActive(active);
        audit.success(active ? "ACTIVATE" : "DEACTIVATE", "Patient", id, id);
        return toResponse(p, false);
    }

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional(readOnly = true)
    public List<CareTeamMemberResponse> careTeam(Long patientId) {
        access.require(patientId, Access.DEMOGRAPHICS_READ);
        List<PatientCareTeamMember> members = careTeam.findByPatientId(patientId);
        Map<Long, AppUser> byId = loadUsers(members.stream().map(m -> m.getId().getClinicianUserId()).toList());
        return members.stream()
                .map(m -> new CareTeamMemberResponse(m.getId().getClinicianUserId(),
                        nameOf(byId.get(m.getId().getClinicianUserId())), m.getReason(), m.getAddedAt()))
                .toList();
    }

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional
    public List<CareTeamMemberResponse> assignClinician(Long patientId, Long clinicianId) {
        access.require(patientId, Access.DEMOGRAPHICS_WRITE);
        requireClinician(clinicianId);
        addToCareTeam(patientId, clinicianId, PatientCareTeamMember.Reason.ASSIGNED);
        audit.record("ASSIGN_CLINICIAN", "Patient", patientId, patientId,
                com.rdss.audit.AuditLog.Outcome.SUCCESS, "clinicianId=" + clinicianId);
        return careTeam(patientId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<CareTeamMemberResponse> removeClinician(Long patientId, Long clinicianId) {
        Patient p = access.require(patientId, Access.DEMOGRAPHICS_WRITE);
        if (clinicianId.equals(p.getPrimaryClinicianId())) {
            throw new BusinessRuleException("Change the primary clinician before removing them from the care team");
        }
        careTeam.deleteById(new PatientCareTeamMember.Key(patientId, clinicianId));
        audit.record("REMOVE_CLINICIAN", "Patient", patientId, patientId,
                com.rdss.audit.AuditLog.Outcome.SUCCESS, "clinicianId=" + clinicianId);
        return careTeam(patientId);
    }

    /** Idempotently grants a clinician care-team access (used by appointments). */
    @Transactional
    public void addToCareTeam(Long patientId, Long clinicianId, PatientCareTeamMember.Reason reason) {
        var key = new PatientCareTeamMember.Key(patientId, clinicianId);
        if (!careTeam.existsById(key)) {
            careTeam.save(new PatientCareTeamMember(patientId, clinicianId, reason));
        }
    }

    /** Resolves the patient id the current user acts for (own record for patients). */
    public Long requireOwnPatientId() {
        AuthenticatedUser user = CurrentUser.require();
        if (!user.is(Role.PATIENT) || user.patientId() == null) {
            throw new AccessDeniedException("Only patient accounts have an own patient record");
        }
        return user.patientId();
    }

    private String nextMrn() {
        return "MRN" + patients.nextMrnNumber();
    }

    private void validatePrimaryClinician(Long clinicianId) {
        if (clinicianId != null) {
            requireClinician(clinicianId);
        }
    }

    private void requireClinician(Long clinicianId) {
        AppUser u = users.findById(clinicianId).orElseThrow(() -> NotFoundException.of("Clinician", clinicianId));
        if (u.getRole() != Role.CLINICIAN || !u.isEnabled()) {
            throw new BusinessRuleException("User " + clinicianId + " is not an active clinician");
        }
    }

    private static boolean includeClinical(AuthenticatedUser user) {
        return !user.is(Role.ADMIN);
    }

    private static void applyDemographics(Patient p, PatientRequest req) {
        p.setPhone(blankToNull(req.phone()));
        p.setEmail(blankToNull(req.email()));
        p.setAddressLine(blankToNull(req.addressLine()));
        p.setCity(blankToNull(req.city()));
        p.setPostalCode(blankToNull(req.postalCode()));
        p.setCountry(blankToNull(req.country()));
        p.setEmergencyContactName(blankToNull(req.emergencyContactName()));
        p.setEmergencyContactPhone(blankToNull(req.emergencyContactPhone()));
        p.setEmergencyContactRelation(blankToNull(req.emergencyContactRelation()));
    }

    private static void applyClinical(Patient p, PatientRequest req) {
        p.setMedicalHistory(blankToNull(req.medicalHistory()));
        p.setAllergies(blankToNull(req.allergies()));
        p.setCurrentMedications(blankToNull(req.currentMedications()));
        p.setSmokingStatus(req.smokingStatus() == null ? Patient.SmokingStatus.UNKNOWN : req.smokingStatus());
        p.setPackYears(req.packYears());
        p.setEnvironmentalExposures(blankToNull(req.environmentalExposures()));
        p.setKnownRespiratoryConditions(blankToNull(req.knownRespiratoryConditions()));
    }

    private Map<Long, AppUser> loadUsers(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private static String nameOf(AppUser u) {
        return u == null ? null : u.getFullName();
    }

    private static ClinicianRef ref(Long id, Map<Long, AppUser> users) {
        return id == null ? null : new ClinicianRef(id, nameOf(users.get(id)));
    }

    private PatientSummary toSummary(Patient p, Map<Long, AppUser> clinicians) {
        return new PatientSummary(p.getId(), p.getMrn(), p.getFirstName(), p.getLastName(), p.getDateOfBirth(),
                p.ageOn(LocalDate.now()), p.getSex(), p.getSmokingStatus(),
                ref(p.getPrimaryClinicianId(), clinicians), p.isActive());
    }

    private PatientResponse toResponse(Patient p, boolean clinical) {
        Map<Long, AppUser> clinicians = p.getPrimaryClinicianId() == null ? Map.of()
                : loadUsers(List.of(p.getPrimaryClinicianId()));
        return new PatientResponse(p.getId(), p.getMrn(), p.getFirstName(), p.getLastName(), p.getDateOfBirth(),
                p.ageOn(LocalDate.now()), p.getSex(), p.getPhone(), p.getEmail(), p.getAddressLine(), p.getCity(),
                p.getPostalCode(), p.getCountry(), p.getEmergencyContactName(), p.getEmergencyContactPhone(),
                p.getEmergencyContactRelation(),
                clinical ? p.getMedicalHistory() : null,
                clinical ? p.getAllergies() : null,
                clinical ? p.getCurrentMedications() : null,
                clinical ? p.getSmokingStatus() : null,
                clinical ? p.getPackYears() : null,
                clinical ? p.getEnvironmentalExposures() : null,
                clinical ? p.getKnownRespiratoryConditions() : null,
                ref(p.getPrimaryClinicianId(), clinicians), p.getUserId() != null, p.isActive(),
                p.getCreatedAt(), p.getUpdatedAt());
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
