package com.rdss.users;

import java.util.Locale;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.PageResponse;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.ConflictException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.CurrentUser;
import com.rdss.common.security.Role;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientRepository;
import com.rdss.users.UserDtos.ClinicianProfileRequest;
import com.rdss.users.UserDtos.ClinicianProfileResponse;
import com.rdss.users.UserDtos.CreateUserRequest;
import com.rdss.users.UserDtos.UpdateUserRequest;
import com.rdss.users.UserDtos.UserResponse;

import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account administration. All operations are restricted to administrators. */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class UserService {

    private final AppUserRepository users;
    private final ClinicianProfileRepository profiles;
    private final DepartmentRepository departments;
    private final PatientRepository patients;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public UserService(AppUserRepository users, ClinicianProfileRepository profiles, DepartmentRepository departments,
                       PatientRepository patients, PasswordEncoder passwordEncoder, AuditService audit) {
        this.users = users;
        this.profiles = profiles;
        this.departments = departments;
        this.patients = patients;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(Role role, String query, Pageable pageable) {
        String like = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        return PageResponse.of(users.search(role, like, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        if (users.existsByUsernameIgnoreCase(req.username().trim())) {
            throw new ConflictException("Username is already taken");
        }
        if (users.existsByEmailIgnoreCase(req.email().trim())) {
            throw new ConflictException("An account with this email already exists");
        }
        Patient linkedPatient = null;
        if (req.role() == Role.PATIENT) {
            if (req.patientId() == null) {
                throw new BusinessRuleException("Patient accounts must be linked to an existing patient record");
            }
            linkedPatient = patients.findById(req.patientId())
                    .orElseThrow(() -> NotFoundException.of("Patient", req.patientId()));
            if (linkedPatient.getUserId() != null) {
                throw new ConflictException("That patient record already has a portal account");
            }
        }
        AppUser user = users.save(new AppUser(req.username().trim(), req.email().trim(),
                passwordEncoder.encode(req.initialPassword()), req.fullName().trim(), req.role()));
        if (linkedPatient != null) {
            linkedPatient.setUserId(user.getId());
        }
        if (req.role() == Role.CLINICIAN) {
            applyProfile(new ClinicianProfile(user), req.clinicianProfile());
        }
        audit.record("CREATE", "User", user.getId(), linkedPatient == null ? null : linkedPatient.getId(),
                Outcome.SUCCESS, "role=" + req.role());
        return toResponse(user);
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest req) {
        AppUser user = find(id);
        if (users.existsByEmailIgnoreCaseAndIdNot(req.email().trim(), id)) {
            throw new ConflictException("An account with this email already exists");
        }
        if (user.getRole() != req.role()) {
            if (user.getRole() == Role.PATIENT || req.role() == Role.PATIENT) {
                throw new BusinessRuleException("Patient accounts cannot be converted to or from staff accounts");
            }
            if (user.getRole() == Role.ADMIN) {
                guardLastAdmin(user);
            }
            audit.record("CHANGE_ROLE", "User", id, null, Outcome.SUCCESS, user.getRole() + "->" + req.role());
            user.setRole(req.role());
        }
        user.setEmail(req.email().trim());
        user.setFullName(req.fullName().trim());
        if (user.getRole() == Role.CLINICIAN) {
            ClinicianProfile profile = profiles.findByUserId(id).orElseGet(() -> new ClinicianProfile(user));
            applyProfile(profile, req.clinicianProfile());
        }
        audit.success("UPDATE", "User", id, null);
        return toResponse(user);
    }

    @Transactional
    public UserResponse setEnabled(Long id, boolean enabled) {
        AppUser user = find(id);
        if (!enabled) {
            if (id.equals(CurrentUser.require().id())) {
                throw new BusinessRuleException("You cannot disable your own account");
            }
            if (user.getRole() == Role.ADMIN) {
                guardLastAdmin(user);
            }
        }
        user.setEnabled(enabled);
        audit.success(enabled ? "ENABLE" : "DISABLE", "User", id, null);
        return toResponse(user);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AppUser user = find(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        audit.success("RESET_PASSWORD", "User", id, null);
    }

    private void guardLastAdmin(AppUser user) {
        if (user.isEnabled() && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new BusinessRuleException("At least one active administrator is required");
        }
    }

    private void applyProfile(ClinicianProfile profile, ClinicianProfileRequest req) {
        if (req != null) {
            profile.setDepartment(req.departmentId() == null ? null : departments.findById(req.departmentId())
                    .orElseThrow(() -> NotFoundException.of("Department", req.departmentId())));
            profile.setTitle(req.title());
            profile.setSpecialty(req.specialty());
            profile.setLicenseNumber(req.licenseNumber());
            profile.setPhone(req.phone());
            if (req.acceptingAppointments() != null) {
                profile.setAcceptingAppointments(req.acceptingAppointments());
            }
        }
        profiles.save(profile);
    }

    private AppUser find(Long id) {
        return users.findById(id).orElseThrow(() -> NotFoundException.of("User", id));
    }

    private UserResponse toResponse(AppUser u) {
        Long patientId = u.getRole() == Role.PATIENT
                ? patients.findByUserId(u.getId()).map(Patient::getId).orElse(null) : null;
        ClinicianProfileResponse profile = u.getRole() == Role.CLINICIAN
                ? profiles.findByUserId(u.getId()).map(UserService::toProfile).orElse(null) : null;
        return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.getRole(),
                u.isEnabled(), u.getLastLoginAt(), u.getCreatedAt(), patientId, profile);
    }

    static ClinicianProfileResponse toProfile(ClinicianProfile p) {
        Department d = p.getDepartment();
        return new ClinicianProfileResponse(d == null ? null : d.getId(), d == null ? null : d.getName(),
                p.getTitle(), p.getSpecialty(), p.getLicenseNumber(), p.getPhone(), p.isAcceptingAppointments());
    }
}
