package com.rdss.hl7.handlers;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.segment.PID;
import ca.uhn.hl7v2.util.Terser;

import com.rdss.appointments.Appointment;
import com.rdss.appointments.AppointmentRepository;
import com.rdss.common.security.Role;
import com.rdss.hl7.Hl7MessageHandler;
import com.rdss.hl7.Hl7ProcessingException;
import com.rdss.hl7.Hl7Support;
import com.rdss.hl7.PatientResolver;
import com.rdss.hl7.SystemAccount;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientCareTeamMember;
import com.rdss.patients.PatientCareTeamRepository;
import com.rdss.system.SettingsService;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.springframework.stereotype.Component;

/**
 * SIU scheduling:
 * <ul>
 *   <li>S12 (new booking): SCH-1 placer appointment id (external id, idempotent), AIS-4 start and
 *       AIS-7/AIS-8 duration (minutes), falling back to SCH-11 start/end; AIP-3 personnel id must be an
 *       RDSS clinician username; SCH-7 reason text. Conflicts with existing bookings return AE.</li>
 *   <li>S15 (cancellation): cancels the appointment with that SCH-1 id.</li>
 * </ul>
 */
@Component
public class SiuHandler implements Hl7MessageHandler {

    private final PatientResolver resolver;
    private final AppointmentRepository appointments;
    private final AppUserRepository users;
    private final PatientCareTeamRepository careTeam;
    private final SystemAccount system;
    private final SettingsService settings;

    public SiuHandler(PatientResolver resolver, AppointmentRepository appointments, AppUserRepository users,
                      PatientCareTeamRepository careTeam, SystemAccount system, SettingsService settings) {
        this.resolver = resolver;
        this.appointments = appointments;
        this.users = users;
        this.careTeam = careTeam;
        this.system = system;
        this.settings = settings;
    }

    @Override
    public Set<String> messageTypes() {
        return Set.of("SIU^S12", "SIU^S15");
    }

    @Override
    public Result handle(Message message, Terser t) throws HL7Exception {
        Patient patient = resolver.require((PID) t.getSegment("/.PID"));
        String externalId = t.get("/.SCH-1-1");
        if (externalId == null || externalId.isBlank()) {
            throw new Hl7ProcessingException("SCH-1 placer appointment id is required");
        }
        final String placerId = externalId.trim();
        var existing = appointments.findByExternalId(placerId);

        if ("S15".equals(t.get("/MSH-9-2"))) {
            Appointment a = existing.orElseThrow(() -> new Hl7ProcessingException("No appointment with SCH-1 id " + placerId));
            if (!a.getPatientId().equals(patient.getId())) {
                throw new Hl7ProcessingException("Appointment belongs to a different patient");
            }
            a.cancel(system.id(), "Cancelled via HL7 SIU^S15", Instant.now());
            return new Result(patient.getId(), "Appointment", String.valueOf(a.getId()), "appointment cancelled");
        }
        if (existing.isPresent()) {
            return new Result(patient.getId(), "Appointment", String.valueOf(existing.get().getId()), "appointment already exists");
        }

        String username = t.get("/.AIP-3-1");
        AppUser clinician = username == null ? null : users.findByUsernameIgnoreCase(username.trim()).orElse(null);
        if (clinician == null || clinician.getRole() != Role.CLINICIAN || !clinician.isEnabled()) {
            throw new Hl7ProcessingException("AIP-3 must identify an active RDSS clinician by username");
        }
        var zone = settings.clinicZone();
        Instant start = Hl7Support.parseInstant(firstNonBlank(t.get("/.AIS-4-1"), t.get("/.SCH-11-4-1")), zone);
        if (start == null) {
            throw new Hl7ProcessingException("Start time is required (AIS-4 or SCH-11.4)");
        }
        Instant end = durationEnd(start, t.get("/.AIS-7"), t.get("/.AIS-8-1"));
        if (end == null) {
            end = Hl7Support.parseInstant(t.get("/.SCH-11-5-1"), zone);
        }
        if (end == null) {
            end = start.plus(Duration.ofMinutes(settings.getInt("appointments.slot_minutes", 30)));
        }
        if (!end.isAfter(start)) {
            throw new Hl7ProcessingException("Appointment end must be after its start");
        }
        if (appointments.clinicianHasOverlap(clinician.getId(), start, end, Appointment.Status.BLOCKING)
                || appointments.patientHasOverlap(patient.getId(), start, end, Appointment.Status.BLOCKING)) {
            throw new Hl7ProcessingException("Time slot conflicts with an existing appointment");
        }
        String reason = firstNonBlank(t.get("/.SCH-7-2"), t.get("/.SCH-7-1"));
        Appointment a = new Appointment(patient.getId(), clinician.getId(), start, end, Appointment.Status.SCHEDULED,
                Appointment.Type.CONSULTATION, reason == null ? null : truncate(reason.trim(), 500), system.id(), null);
        a.setExternalId(placerId);
        a = appointments.saveAndFlush(a);
        var key = new PatientCareTeamMember.Key(patient.getId(), clinician.getId());
        if (!careTeam.existsById(key)) {
            careTeam.save(new PatientCareTeamMember(patient.getId(), clinician.getId(), PatientCareTeamMember.Reason.APPOINTMENT));
        }
        return new Result(patient.getId(), "Appointment", String.valueOf(a.getId()), "appointment booked");
    }

    private static Instant durationEnd(Instant start, String duration, String units) {
        if (duration == null || duration.isBlank()) {
            return null;
        }
        long minutes;
        try {
            minutes = Long.parseLong(duration.trim());
        } catch (NumberFormatException ex) {
            throw new Hl7ProcessingException("AIS-7 duration must be numeric");
        }
        if (units != null && units.trim().equalsIgnoreCase("H")) {
            minutes *= 60;
        }
        if (minutes <= 0 || minutes > 600) {
            throw new Hl7ProcessingException("AIS-7 duration out of range");
        }
        return start.plus(Duration.ofMinutes(minutes));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b != null && !b.isBlank() ? b : null);
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
