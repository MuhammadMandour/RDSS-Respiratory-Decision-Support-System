package com.rdss.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.rdss.appointments.ClinicianAvailability;
import com.rdss.appointments.ClinicianAvailabilityRepository;
import com.rdss.auth.JwtService;
import com.rdss.common.security.Role;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientCareTeamMember;
import com.rdss.patients.PatientCareTeamRepository;
import com.rdss.patients.PatientRepository;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;
import com.rdss.users.ClinicianProfile;
import com.rdss.users.ClinicianProfileRepository;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates synthetic users/patients directly through repositories and issues tokens, so tests
 * can focus on the behaviour under test. Every call uses unique identifiers.
 */
@TestComponent
public class TestFixtures {

    public static final String PASSWORD = "Test-Pass-12345";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final AppUserRepository users;
    private final PatientRepository patients;
    private final PatientCareTeamRepository careTeam;
    private final ClinicianProfileRepository profiles;
    private final ClinicianAvailabilityRepository availability;
    private final JwtService jwtService;
    private final String passwordHash;

    public TestFixtures(AppUserRepository users, PatientRepository patients, PatientCareTeamRepository careTeam,
                        ClinicianProfileRepository profiles, ClinicianAvailabilityRepository availability,
                        JwtService jwtService, PasswordEncoder encoder) {
        this.users = users;
        this.patients = patients;
        this.careTeam = careTeam;
        this.profiles = profiles;
        this.availability = availability;
        this.jwtService = jwtService;
        this.passwordHash = encoder.encode(PASSWORD);
    }

    public AppUser admin() {
        return user(Role.ADMIN, "admin");
    }

    public AppUser clinician() {
        AppUser u = user(Role.CLINICIAN, "clin");
        profiles.save(new ClinicianProfile(u));
        return u;
    }

    public AppUser user(Role role, String prefix) {
        String name = prefix + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
        return users.save(new AppUser(name, name + "@test.local", passwordHash, "Test " + name, role));
    }

    /** Patient record without a portal account. */
    public Patient patient() {
        return patients.save(new Patient("MRN" + patients.nextMrnNumber(), "Synthetic", "Patient" + SEQ.incrementAndGet(),
                LocalDate.of(1980, 1, 1), Patient.Sex.UNKNOWN));
    }

    /** Patient record linked to a new PATIENT account. */
    public PatientWithAccount patientWithAccount() {
        AppUser account = user(Role.PATIENT, "pt");
        Patient p = patient();
        p.setUserId(account.getId());
        p = patients.save(p);
        return new PatientWithAccount(p, account);
    }

    public void assign(Patient patient, AppUser clinician) {
        careTeam.save(new PatientCareTeamMember(patient.getId(), clinician.getId(),
                PatientCareTeamMember.Reason.ASSIGNED));
    }

    /** Every day of the week, 08:00-18:00 clinic time, 30-minute slots. */
    public void fullWeekAvailability(AppUser clinician) {
        for (DayOfWeek d : DayOfWeek.values()) {
            availability.save(new ClinicianAvailability(clinician.getId(), d, LocalTime.of(8, 0), LocalTime.of(18, 0),
                    30, LocalDate.now().minusDays(1), null));
        }
    }

    public String token(AppUser user) {
        return "Bearer " + jwtService.issue(user).token();
    }

    public record PatientWithAccount(Patient patient, AppUser account) {
    }
}
