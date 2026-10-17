package com.rdss.seed;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

import com.rdss.appointments.Appointment;
import com.rdss.appointments.AppointmentRepository;
import com.rdss.appointments.ClinicianAvailability;
import com.rdss.appointments.ClinicianAvailabilityRepository;
import com.rdss.common.security.Role;
import com.rdss.encounters.ClinicalEncounter;
import com.rdss.encounters.ClinicalEncounterRepository;
import com.rdss.encounters.Prescription;
import com.rdss.encounters.PrescriptionRepository;
import com.rdss.encounters.TreatmentPlan;
import com.rdss.encounters.TreatmentPlanRepository;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientCareTeamMember;
import com.rdss.patients.PatientCareTeamRepository;
import com.rdss.patients.PatientRepository;
import com.rdss.respiratory.Observation;
import com.rdss.respiratory.ObservationRepository;
import com.rdss.respiratory.PulmonaryFunctionTest;
import com.rdss.respiratory.PulmonaryFunctionTestRepository;
import com.rdss.respiratory.RespiratoryHistory;
import com.rdss.respiratory.RespiratoryHistoryRepository;
import com.rdss.system.SettingsService;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;
import com.rdss.users.ClinicianProfile;
import com.rdss.users.ClinicianProfileRepository;
import com.rdss.users.Department;
import com.rdss.users.DepartmentRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds SYNTHETIC demonstration data. All names, identifiers and clinical values are invented;
 * none relate to real people. Skips entirely if the demo clinician already exists.
 */
@Component
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    static final String MARKER_USERNAME = "dr.hale";

    private final AppUserRepository users;
    private final DepartmentRepository departments;
    private final ClinicianProfileRepository profiles;
    private final PatientRepository patients;
    private final PatientCareTeamRepository careTeam;
    private final RespiratoryHistoryRepository histories;
    private final ClinicalEncounterRepository encounters;
    private final ObservationRepository observations;
    private final PulmonaryFunctionTestRepository pfts;
    private final TreatmentPlanRepository plans;
    private final PrescriptionRepository prescriptions;
    private final ClinicianAvailabilityRepository availability;
    private final AppointmentRepository appointments;
    private final SettingsService settings;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DemoDataSeeder(AppUserRepository users, DepartmentRepository departments,
                          ClinicianProfileRepository profiles, PatientRepository patients,
                          PatientCareTeamRepository careTeam, RespiratoryHistoryRepository histories,
                          ClinicalEncounterRepository encounters, ObservationRepository observations,
                          PulmonaryFunctionTestRepository pfts, TreatmentPlanRepository plans,
                          PrescriptionRepository prescriptions, ClinicianAvailabilityRepository availability,
                          AppointmentRepository appointments, SettingsService settings) {
        this.users = users;
        this.departments = departments;
        this.profiles = profiles;
        this.patients = patients;
        this.careTeam = careTeam;
        this.histories = histories;
        this.encounters = encounters;
        this.observations = observations;
        this.pfts = pfts;
        this.plans = plans;
        this.prescriptions = prescriptions;
        this.availability = availability;
        this.appointments = appointments;
        this.settings = settings;
    }

    @Transactional
    public void seed(String passwordHash) {
        if (users.existsByUsernameIgnoreCase(MARKER_USERNAME)) {
            log.info("Demo data already present; skipping");
            return;
        }
        log.info("Seeding synthetic demo data");

        if (!users.existsByUsernameIgnoreCase("admin")) {
            users.save(new AppUser("admin", "admin@rdss.local", passwordHash, "Alex Morgan (Admin)", Role.ADMIN));
        }
        Department pulm = departments.save(new Department("Pulmonology", "Adult respiratory outpatient clinic"));
        departments.save(new Department("Respiratory Physiology", "Lung function laboratory"));

        AppUser hale = clinician("dr.hale", "Dr. Amira Hale", "Consultant Pulmonologist", pulm, passwordHash);
        AppUser osei = clinician("dr.osei", "Dr. Kwame Osei", "Respiratory Physician", pulm, passwordHash);

        // Synthetic patients (names and MRNs are fabricated for demonstration only).
        Patient asthma = patient("Jordan", "Reyes", LocalDate.of(1994, 3, 14), Patient.Sex.FEMALE,
                Patient.SmokingStatus.NEVER, null, "Seasonal allergic rhinitis", "None known",
                "Salbutamol 100 mcg inhaler PRN; Budesonide/formoterol 200/6 BD", "Asthma", hale);
        Patient copd = patient("Samuel", "Okafor", LocalDate.of(1956, 9, 2), Patient.Sex.MALE,
                Patient.SmokingStatus.FORMER, new BigDecimal("40.0"), "Hypertension; type 2 diabetes",
                "Penicillin (rash)", "Tiotropium 18 mcg OD; Metformin 500 mg BD; Amlodipine 5 mg OD", "COPD", hale);
        Patient acute = patient("Maria", "Novak", LocalDate.of(1971, 11, 23), Patient.Sex.FEMALE,
                Patient.SmokingStatus.CURRENT, new BigDecimal("22.5"), "No significant history", "None known",
                "None", null, osei);
        Patient followUp = patient("Daniel", "Kim", LocalDate.of(1988, 6, 5), Patient.Sex.MALE,
                Patient.SmokingStatus.NEVER, null, "Previous community-acquired pneumonia (treated)",
                "None known", "None", null, osei);

        AppUser jordanAccount = users.save(new AppUser("patient.jordan", "jordan.reyes@example.test", passwordHash,
                "Jordan Reyes", Role.PATIENT));
        asthma.setUserId(jordanAccount.getId());
        AppUser samuelAccount = users.save(new AppUser("patient.samuel", "samuel.okafor@example.test", passwordHash,
                "Samuel Okafor", Role.PATIENT));
        copd.setUserId(samuelAccount.getId());

        histories.save(new RespiratoryHistory(asthma.getId(), 1, null, true, false, false, false, false, false,
                false, null, null, false, 1, 0, false, "ICS/LABA maintenance and reliever", null,
                "Mother has asthma", "Influenza 2025", null, "Symptoms worse in spring", hale.getId()));
        histories.save(new RespiratoryHistory(copd.getId(), 1, null, false, true, false, false, false, false,
                true, 88, 92, false, 2, 1, false, "LAMA", "Former shipyard worker", null,
                "Influenza 2025; pneumococcal 2023", "Hypertension, T2DM",
                "Target SpO2 88-92% documented by respiratory team", hale.getId()));

        Instant now = Instant.now();
        // Finalized COPD review with spirometry.
        var copdVisit = new ClinicalEncounter(copd.getId(), hale.getId(), ClinicalEncounter.Type.OUTPATIENT,
                now.minus(Duration.ofDays(30)));
        copdVisit.setChiefComplaint("Routine COPD review");
        copdVisit.setDyspnea(true);
        copdVisit.setMmrcGrade(2);
        copdVisit.setCatScore(18);
        copdVisit.setCough(true);
        copdVisit.setSputum(true);
        copdVisit.setSputumDescription("White, morning");
        copdVisit.setPhysicalExam("Reduced breath sounds bilaterally, no crackles");
        copdVisit.setAssessmentNotes("Stable COPD, symptomatic");
        copdVisit.setPlanNotes("Continue LAMA, pulmonary rehabilitation referral");
        copdVisit = encounters.save(copdVisit);
        copdVisit.finalizeBy(hale.getId(), now.minus(Duration.ofDays(30)).plus(Duration.ofMinutes(40)));
        observations.save(new Observation(copd.getId(), copdVisit.getId(), now.minus(Duration.ofDays(30)), 20, 88,
                138, 82, new BigDecimal("36.8"), 91, false, null, null, Observation.Consciousness.ALERT, null,
                null, hale.getId()));
        pfts.save(new PulmonaryFunctionTest(copd.getId(), copdVisit.getId(), now.minus(Duration.ofDays(30)), true,
                new BigDecimal("1.45"), new BigDecimal("2.90"), 52, 78, 61, new BigDecimal("4.0"), 60, "A",
                "Post-bronchodilator FEV1/FVC 0.50 consistent with persistent airflow obstruction", hale.getId()));
        plans.save(plan(copd.getId(), copdVisit.getId(), hale.getId()));
        prescriptions.save(new Prescription(copd.getId(), copdVisit.getId(), "Tiotropium", "18 mcg", "Inhaled",
                "Once daily", "Ongoing", "30 capsules", 5, "Use with HandiHaler device", hale.getId()));

        // Open acute encounter with abnormal vital signs (useful for CDSS demonstrations).
        var acuteVisit = new ClinicalEncounter(acute.getId(), osei.getId(), ClinicalEncounter.Type.OUTPATIENT,
                now.minus(Duration.ofHours(1)));
        acuteVisit.setChiefComplaint("Productive cough and breathlessness for 4 days");
        acuteVisit.setCough(true);
        acuteVisit.setCoughDurationDays(4);
        acuteVisit.setSputum(true);
        acuteVisit.setSputumDescription("Green");
        acuteVisit.setDyspnea(true);
        acuteVisit.setFever(true);
        acuteVisit.setSymptomDurationDays(4);
        acuteVisit = encounters.save(acuteVisit);
        observations.save(new Observation(acute.getId(), acuteVisit.getId(), now.minus(Duration.ofMinutes(45)), 24,
                112, 102, 64, new BigDecimal("38.6"), 92, false, null, null, Observation.Consciousness.ALERT,
                null, "Triage observations", osei.getId()));

        // Weekly availability: Mon-Fri 09:00-12:00 and 13:00-16:00, 30-minute slots.
        ZoneId zone = settings.clinicZone();
        LocalDate today = LocalDate.now(zone);
        for (AppUser c : new AppUser[] {hale, osei}) {
            for (DayOfWeek d : new DayOfWeek[] {DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
                availability.save(new ClinicianAvailability(c.getId(), d, LocalTime.of(9, 0), LocalTime.of(12, 0),
                        30, today.minusDays(7), null));
                availability.save(new ClinicianAvailability(c.getId(), d, LocalTime.of(13, 0), LocalTime.of(16, 0),
                        30, today.minusDays(7), null));
            }
        }

        LocalDate nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        appointment(asthma, hale, nextMonday, LocalTime.of(9, 0), Appointment.Type.FOLLOW_UP,
                "Asthma control review", zone);
        appointment(copd, hale, nextMonday, LocalTime.of(10, 0), Appointment.Type.SPIROMETRY,
                "Repeat spirometry", zone);
        appointment(followUp, osei, nextMonday.plusDays(1), LocalTime.of(13, 30), Appointment.Type.IMAGING_REVIEW,
                "Follow-up chest X-ray review", zone);
        log.info("Synthetic demo data seeded");
    }

    private AppUser clinician(String username, String name, String specialty, Department dept, String hash) {
        AppUser u = users.save(new AppUser(username, username + "@rdss.local", hash, name, Role.CLINICIAN));
        ClinicianProfile p = new ClinicianProfile(u);
        p.setDepartment(dept);
        p.setTitle("Dr.");
        p.setSpecialty(specialty);
        p.setLicenseNumber("DEMO-" + username.toUpperCase().replace(".", "-"));
        profiles.save(p);
        return u;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Patient patient(String first, String last, LocalDate dob, Patient.Sex sex, Patient.SmokingStatus smoking,
                            BigDecimal packYears, String history, String allergies, String meds, String conditions,
                            AppUser primary) {
        Patient p = new Patient("MRN" + patients.nextMrnNumber(), first, last, dob, sex);
        p.setSmokingStatus(smoking);
        p.setPackYears(packYears);
        p.setMedicalHistory(history);
        p.setAllergies(allergies);
        p.setCurrentMedications(meds);
        p.setKnownRespiratoryConditions(conditions);
        p.setPhone("+1 555 0100");
        p.setEmail(first.toLowerCase() + "." + last.toLowerCase() + "@example.test");
        p.setCity("Springfield");
        p.setCountry("Demo");
        p.setEmergencyContactName("Demo Contact");
        p.setEmergencyContactPhone("+1 555 0199");
        p.setEmergencyContactRelation("Partner");
        p.setPrimaryClinicianId(primary.getId());
        p = patients.save(p);
        careTeam.save(new PatientCareTeamMember(p.getId(), primary.getId(), PatientCareTeamMember.Reason.PRIMARY));
        return p;
    }

    private static TreatmentPlan plan(Long patientId, Long encounterId, Long clinicianId) {
        var plan = new TreatmentPlan(patientId, encounterId, "COPD management plan",
                LocalDate.now().minusDays(30), clinicianId);
        plan.setGoals("Reduce exacerbations; improve exercise tolerance");
        plan.setInterventions("LAMA maintenance; pulmonary rehabilitation; annual influenza vaccination");
        plan.setFollowUpInstructions("Review in 3 months with repeat spirometry");
        plan.setReviewDate(LocalDate.now().plusDays(60));
        return plan;
    }

    private void appointment(Patient p, AppUser clinician, LocalDate date, LocalTime time, Appointment.Type type,
                             String reason, ZoneId zone) {
        Instant start = date.atTime(time).atZone(zone).toInstant();
        appointments.save(new Appointment(p.getId(), clinician.getId(), start, start.plus(Duration.ofMinutes(30)),
                Appointment.Status.SCHEDULED, type, reason, clinician.getId(), null));
        var key = new PatientCareTeamMember.Key(p.getId(), clinician.getId());
        if (!careTeam.existsById(key)) {
            careTeam.save(new PatientCareTeamMember(p.getId(), clinician.getId(),
                    PatientCareTeamMember.Reason.APPOINTMENT));
        }
    }
}
