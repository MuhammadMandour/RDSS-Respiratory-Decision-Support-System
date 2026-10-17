package com.rdss.auth;

import java.time.Instant;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.auth.AuthDtos.AuthResponse;
import com.rdss.auth.AuthDtos.ChangePasswordRequest;
import com.rdss.auth.AuthDtos.LoginRequest;
import com.rdss.auth.AuthDtos.RegisterPatientRequest;
import com.rdss.auth.AuthDtos.UserInfo;
import com.rdss.common.error.ConflictException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;
import com.rdss.common.security.Role;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientRepository;
import com.rdss.patients.PatientService;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid username or password";

    private final AppUserRepository users;
    private final PatientRepository patients;
    private final PatientService patientService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttempts;
    private final AuditService audit;
    /** Used to equalize timing when the username does not exist. */
    private final String dummyHash;

    public AuthService(AppUserRepository users, PatientRepository patients, PatientService patientService,
                       PasswordEncoder passwordEncoder, JwtService jwtService, LoginAttemptService loginAttempts,
                       AuditService audit) {
        this.users = users;
        this.patients = patients;
        this.patientService = patientService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttempts = loginAttempts;
        this.audit = audit;
        this.dummyHash = passwordEncoder.encode("timing-equalization-placeholder");
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        String username = req.username().trim();
        if (loginAttempts.isLocked(username)) {
            audit.recordAs(null, "LOGIN", "User", null, null, Outcome.DENIED, "locked username=" + username);
            throw new LockedException("Too many failed attempts. Try again later.");
        }
        AppUser user = users.findByUsernameIgnoreCase(username).orElse(null);
        boolean passwordOk = passwordEncoder.matches(req.password(),
                user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !passwordOk || !user.isEnabled()) {
            loginAttempts.recordFailure(username);
            audit.recordAs(null, "LOGIN", "User", user == null ? null : user.getId(), null, Outcome.DENIED,
                    "username=" + username);
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        loginAttempts.recordSuccess(username);
        user.setLastLoginAt(Instant.now());
        audit.recordAs(principal(user), "LOGIN", "User", user.getId(), null, Outcome.SUCCESS, null);
        return issue(user);
    }

    /** Patient self-registration: creates a PATIENT account and its linked patient record. */
    @Transactional
    public AuthResponse registerPatient(RegisterPatientRequest req) {
        if (users.existsByUsernameIgnoreCase(req.username().trim())) {
            throw new ConflictException("Username is already taken");
        }
        if (users.existsByEmailIgnoreCase(req.email().trim())) {
            throw new ConflictException("An account with this email already exists");
        }
        String fullName = req.firstName().trim() + " " + req.lastName().trim();
        AppUser user = users.save(new AppUser(req.username().trim(), req.email().trim(),
                passwordEncoder.encode(req.password()), fullName, Role.PATIENT));
        Patient patient = patientService.createSelfRegistered(user, req.firstName(), req.lastName(),
                req.dateOfBirth(), req.sex(), req.phone());
        audit.recordAs(principal(user), "REGISTER", "User", user.getId(), patient.getId(), Outcome.SUCCESS, null);
        return issue(user);
    }

    @Transactional(readOnly = true)
    public UserInfo me() {
        AuthenticatedUser current = CurrentUser.require();
        AppUser user = users.findById(current.id()).orElseThrow(() -> NotFoundException.of("User", current.id()));
        return info(user);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest req) {
        AuthenticatedUser current = CurrentUser.require();
        AppUser user = users.findById(current.id()).orElseThrow(() -> NotFoundException.of("User", current.id()));
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            audit.record("CHANGE_PASSWORD", "User", user.getId(), null, Outcome.DENIED, null);
            throw new BadCredentialsException("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        audit.record("CHANGE_PASSWORD", "User", user.getId(), null, Outcome.SUCCESS, null);
    }

    private AuthResponse issue(AppUser user) {
        JwtService.IssuedToken token = jwtService.issue(user);
        return new AuthResponse(token.token(), "Bearer", token.expiresAt(), info(user));
    }

    private UserInfo info(AppUser user) {
        Long patientId = user.getRole() == Role.PATIENT
                ? patients.findByUserId(user.getId()).map(Patient::getId).orElse(null)
                : null;
        return new UserInfo(user.getId(), user.getUsername(), user.getFullName(), user.getEmail(), user.getRole(),
                patientId);
    }

    private static AuthenticatedUser principal(AppUser user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getFullName(), user.getRole(), null);
    }
}
