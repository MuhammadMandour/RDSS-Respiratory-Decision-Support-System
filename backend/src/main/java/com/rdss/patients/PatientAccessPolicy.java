package com.rdss.patients;

import com.rdss.audit.AuditService;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;
import com.rdss.common.security.Role;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single place that decides whether the current user may touch a given patient's data.
 * Every patient-scoped service call goes through here; the URL id is never trusted.
 *
 * <ul>
 *   <li>PATIENT: only their own linked record. Any other id yields 404 so existence is not leaked.</li>
 *   <li>CLINICIAN: patients on their care team (read and clinical write).</li>
 *   <li>ADMIN: demographics and administrative data only, no clinical content.</li>
 * </ul>
 */
@Component
public class PatientAccessPolicy {

    public enum Access {
        /** Demographics, appointments, invoices. */
        DEMOGRAPHICS_READ,
        /** Demographic/profile updates. */
        DEMOGRAPHICS_WRITE,
        /** Encounters, observations, PFTs, CDSS, imaging. */
        CLINICAL_READ,
        /** Creating or changing clinical data. */
        CLINICAL_WRITE
    }

    private final PatientRepository patients;
    private final PatientCareTeamRepository careTeam;
    private final AuditService audit;

    public PatientAccessPolicy(PatientRepository patients, PatientCareTeamRepository careTeam, AuditService audit) {
        this.patients = patients;
        this.careTeam = careTeam;
        this.audit = audit;
    }

    /**
     * Verifies access and returns the patient. Throws 404 (patients, unknown ids) or 403 (staff).
     */
    @Transactional(readOnly = true)
    public Patient require(Long patientId, Access access) {
        AuthenticatedUser user = CurrentUser.require();
        if (!isAllowed(user, patientId, access)) {
            audit.denied(access.name(), "Patient", patientId, patientId);
            if (user.is(Role.PATIENT)) {
                throw NotFoundException.of("Patient", patientId);
            }
            throw new AccessDeniedException("Not permitted for this patient");
        }
        return patients.findById(patientId).orElseThrow(() -> NotFoundException.of("Patient", patientId));
    }

    /** Non-throwing check, for filtering. */
    @Transactional(readOnly = true)
    public boolean isAllowed(AuthenticatedUser user, Long patientId, Access access) {
        if (patientId == null) {
            return false;
        }
        return switch (user.role()) {
            case PATIENT -> patientId.equals(user.patientId())
                    && (access == Access.DEMOGRAPHICS_READ || access == Access.DEMOGRAPHICS_WRITE
                    || access == Access.CLINICAL_READ);
            case CLINICIAN -> isOnCareTeam(user.id(), patientId);
            case ADMIN -> access == Access.DEMOGRAPHICS_READ || access == Access.DEMOGRAPHICS_WRITE;
        };
    }

    public boolean isOnCareTeam(Long clinicianUserId, Long patientId) {
        return careTeam.existsById(new PatientCareTeamMember.Key(patientId, clinicianUserId));
    }
}
