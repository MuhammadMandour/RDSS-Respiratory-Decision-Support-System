package com.rdss.appointments;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rdss.appointments.AppointmentDtos.AppointmentResponse;
import com.rdss.appointments.AppointmentDtos.AvailabilityWindowRequest;
import com.rdss.appointments.AppointmentDtos.AvailabilityWindowResponse;
import com.rdss.appointments.AppointmentDtos.BookAppointmentRequest;
import com.rdss.appointments.AppointmentDtos.SlotResponse;
import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.PageResponse;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.ConflictException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;
import com.rdss.common.security.Role;
import com.rdss.encounters.ClinicalEncounter;
import com.rdss.encounters.EncounterDtos.CreateEncounterRequest;
import com.rdss.encounters.EncounterDtos.EncounterResponse;
import com.rdss.encounters.EncounterService;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientAccessPolicy;
import com.rdss.patients.PatientAccessPolicy.Access;
import com.rdss.patients.PatientCareTeamMember;
import com.rdss.patients.PatientRepository;
import com.rdss.patients.PatientService;
import com.rdss.system.SettingsService;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;
import com.rdss.users.ClinicianProfileRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduling. Conflicts are checked in the service for a friendly error and enforced by
 * PostgreSQL exclusion constraints, so concurrent double-booking is impossible.
 */
@Service
public class AppointmentService {

    static final int MAX_SLOT_RANGE_DAYS = 31;

    private final AppointmentRepository appointments;
    private final ClinicianAvailabilityRepository availability;
    private final AppUserRepository users;
    private final ClinicianProfileRepository profiles;
    private final PatientRepository patients;
    private final PatientService patientService;
    private final PatientAccessPolicy access;
    private final EncounterService encounterService;
    private final SettingsService settings;
    private final AuditService audit;

    public AppointmentService(AppointmentRepository appointments, ClinicianAvailabilityRepository availability,
                              AppUserRepository users, ClinicianProfileRepository profiles,
                              PatientRepository patients, PatientService patientService, PatientAccessPolicy access,
                              EncounterService encounterService, SettingsService settings, AuditService audit) {
        this.appointments = appointments;
        this.availability = availability;
        this.users = users;
        this.profiles = profiles;
        this.patients = patients;
        this.patientService = patientService;
        this.access = access;
        this.encounterService = encounterService;
        this.settings = settings;
        this.audit = audit;
    }

    // ---- Availability ----

    @Transactional(readOnly = true)
    public List<AvailabilityWindowResponse> getAvailability(Long clinicianId) {
        requireClinicianUser(clinicianId);
        return availability.findByClinicianIdAndActiveTrueOrderByDayOfWeekAscStartTimeAsc(clinicianId).stream()
                .map(AvailabilityWindowResponse::from).toList();
    }

    /** Replaces a clinician's weekly schedule. Existing windows are deactivated, not deleted. */
    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional
    public List<AvailabilityWindowResponse> replaceAvailability(Long clinicianId,
                                                               List<AvailabilityWindowRequest> windows) {
        AuthenticatedUser user = CurrentUser.require();
        if (user.is(Role.CLINICIAN) && !user.id().equals(clinicianId)) {
            throw new AccessDeniedException("Clinicians may only change their own availability");
        }
        requireClinicianUser(clinicianId);
        validateNoOverlap(windows);
        availability.findByClinicianIdAndActiveTrueOrderByDayOfWeekAscStartTimeAsc(clinicianId)
                .forEach(ClinicianAvailability::deactivate);
        LocalDate today = LocalDate.now(settings.clinicZone());
        for (AvailabilityWindowRequest w : windows) {
            availability.save(new ClinicianAvailability(clinicianId, w.dayOfWeek(), w.startTime(), w.endTime(),
                    w.slotMinutes(), w.validFrom() == null ? today : w.validFrom(), w.validTo()));
        }
        audit.record("REPLACE", "ClinicianAvailability", clinicianId, null, Outcome.SUCCESS,
                "windows=" + windows.size());
        return getAvailability(clinicianId);
    }

    @Transactional(readOnly = true)
    public List<SlotResponse> freeSlots(Long clinicianId, LocalDate from, LocalDate to) {
        requireClinicianUser(clinicianId);
        if (to.isBefore(from) || Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() > MAX_SLOT_RANGE_DAYS) {
            throw new BusinessRuleException("Date range must be ascending and at most " + MAX_SLOT_RANGE_DAYS + " days");
        }
        ZoneId zone = settings.clinicZone();
        Instant rangeStart = from.atStartOfDay(zone).toInstant();
        Instant rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
        var windows = availability.findByClinicianIdAndActiveTrueOrderByDayOfWeekAscStartTimeAsc(clinicianId);
        var blocking = appointments.findBlocking(clinicianId, rangeStart, rangeEnd, Appointment.Status.BLOCKING);
        return SlotCalculator.freeSlots(windows, from, to, zone, blocking, Instant.now()).stream()
                .map(s -> new SlotResponse(s.startsAt(), s.endsAt())).toList();
    }

    // ---- Booking ----

    @Transactional
    public AppointmentResponse book(BookAppointmentRequest req) {
        AuthenticatedUser user = CurrentUser.require();
        Long patientId = user.is(Role.PATIENT) ? patientService.requireOwnPatientId() : req.patientId();
        if (patientId == null) {
            throw new BusinessRuleException("patientId is required");
        }
        access.require(patientId, Access.DEMOGRAPHICS_READ);
        Patient patient = patients.findById(patientId).orElseThrow(() -> NotFoundException.of("Patient", patientId));
        if (!patient.isActive()) {
            throw new BusinessRuleException("Appointments cannot be booked for an inactive patient");
        }
        requireBookableClinician(req.clinicianId());
        Instant endsAt = resolveEnd(user, req.clinicianId(), req.startsAt(), req.durationMinutes());
        Appointment a = createChecked(patientId, req.clinicianId(), req.startsAt(), endsAt,
                user.is(Role.PATIENT) ? Appointment.Status.REQUESTED : Appointment.Status.SCHEDULED, req.type(),
                trim(req.reason()), user.id(), null);
        patientService.addToCareTeam(patientId, req.clinicianId(), PatientCareTeamMember.Reason.APPOINTMENT);
        audit.success("BOOK", "Appointment", a.getId(), patientId);
        return toResponse(a);
    }

    @Transactional
    public AppointmentResponse reschedule(Long id, Instant newStart, Integer durationMinutes) {
        AuthenticatedUser user = CurrentUser.require();
        Appointment old = requireAccessible(id);
        enforcePatientCutoff(user, old);
        Instant endsAt = resolveEnd(user, old.getClinicianId(), newStart, durationMinutes);
        old.cancel(user.id(), "Rescheduled", Instant.now());
        // Flush the cancellation first: Hibernate orders inserts before updates, and the new
        // slot may overlap the old one, which the exclusion constraint would otherwise reject.
        appointments.saveAndFlush(old);
        Appointment replacement = createChecked(old.getPatientId(), old.getClinicianId(), newStart, endsAt,
                user.is(Role.PATIENT) ? Appointment.Status.REQUESTED : Appointment.Status.SCHEDULED,
                old.getType(), old.getReason(), user.id(), old.getId());
        audit.record("RESCHEDULE", "Appointment", replacement.getId(), old.getPatientId(), Outcome.SUCCESS,
                "from=" + id);
        return toResponse(replacement);
    }

    @Transactional
    public AppointmentResponse cancel(Long id, String reason) {
        AuthenticatedUser user = CurrentUser.require();
        Appointment a = requireAccessible(id);
        enforcePatientCutoff(user, a);
        a.cancel(user.id(), reason.trim(), Instant.now());
        audit.success("CANCEL", "Appointment", id, a.getPatientId());
        return toResponse(a);
    }

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional
    public AppointmentResponse changeStatus(Long id, Appointment.Status status) {
        Appointment a = requireAccessible(id);
        if (status == Appointment.Status.NO_SHOW && a.getStartsAt().isAfter(Instant.now())) {
            throw new BusinessRuleException("An appointment cannot be marked as no-show before it starts");
        }
        a.transition(status);
        audit.record("STATUS", "Appointment", id, a.getPatientId(), Outcome.SUCCESS, status.name());
        return toResponse(a);
    }

    /** Opens a clinical encounter for the appointment (checks the patient in if needed). */
    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public EncounterResponse startEncounter(Long id) {
        Appointment a = requireAccessible(id);
        if (a.getEncounterId() != null) {
            throw new ConflictException("Appointment " + id + " already has encounter " + a.getEncounterId());
        }
        if (a.getStatus() == Appointment.Status.REQUESTED) {
            a.transition(Appointment.Status.SCHEDULED);
        }
        if (a.getStatus() == Appointment.Status.SCHEDULED) {
            a.transition(Appointment.Status.CHECKED_IN);
        }
        if (a.getStatus() != Appointment.Status.CHECKED_IN) {
            throw new ConflictException("Cannot start an encounter for a " + a.getStatus() + " appointment");
        }
        var type = a.getType() == Appointment.Type.TELEHEALTH
                ? ClinicalEncounter.Type.TELEHEALTH : ClinicalEncounter.Type.OUTPATIENT;
        EncounterResponse encounter = encounterService.create(a.getPatientId(),
                new CreateEncounterRequest(type, Instant.now(), a.getReason()));
        a.linkEncounter(encounter.id());
        return encounter;
    }

    @Transactional(readOnly = true)
    public AppointmentResponse get(Long id) {
        Appointment a = requireAccessible(id);
        audit.success("READ", "Appointment", id, a.getPatientId());
        return toResponse(a);
    }

    @Transactional(readOnly = true)
    public PageResponse<AppointmentResponse> search(Long patientId, Long clinicianId, Appointment.Status status,
                                                    Instant from, Instant to, Pageable pageable) {
        AuthenticatedUser user = CurrentUser.require();
        Page<Appointment> page = switch (user.role()) {
            // Patients are always pinned to their own record regardless of the requested id.
            case PATIENT -> appointments.search(patientService.requireOwnPatientId(), clinicianId, status, from, to,
                    pageable);
            case CLINICIAN -> appointments.searchForClinician(user.id(), patientId, clinicianId, status, from, to,
                    pageable);
            case ADMIN -> appointments.search(patientId, clinicianId, status, from, to, pageable);
        };
        return PageResponse.of(page, mapper(page.getContent()));
    }

    // ---- helpers ----

    private Appointment createChecked(Long patientId, Long clinicianId, Instant startsAt, Instant endsAt,
                                      Appointment.Status status, Appointment.Type type, String reason, Long createdBy,
                                      Long rescheduledFrom) {
        if (!startsAt.isAfter(Instant.now())) {
            throw new BusinessRuleException("Appointments must start in the future");
        }
        if (appointments.clinicianHasOverlap(clinicianId, startsAt, endsAt, Appointment.Status.BLOCKING)) {
            throw new ConflictException("The clinician already has an appointment at that time");
        }
        if (appointments.patientHasOverlap(patientId, startsAt, endsAt, Appointment.Status.BLOCKING)) {
            throw new ConflictException("The patient already has an appointment at that time");
        }
        // The exclusion constraints still guard against a concurrent booking between the check and the insert.
        return appointments.saveAndFlush(new Appointment(patientId, clinicianId, startsAt, endsAt, status, type,
                reason, createdBy, rescheduledFrom));
    }

    /**
     * Patients must pick an offered slot inside the booking horizon. Staff may book any future
     * time (e.g. overbooking an urgent review) but still cannot create overlaps.
     */
    private Instant resolveEnd(AuthenticatedUser user, Long clinicianId, Instant startsAt, Integer durationMinutes) {
        ZoneId zone = settings.clinicZone();
        if (user.is(Role.PATIENT)) {
            int horizon = settings.getInt("appointments.patient_booking_horizon_days", 90);
            LocalDate date = startsAt.atZone(zone).toLocalDate();
            if (date.isAfter(LocalDate.now(zone).plusDays(horizon))) {
                throw new BusinessRuleException("Appointments can be booked at most " + horizon + " days ahead");
            }
            return freeSlots(clinicianId, date, date).stream()
                    .filter(s -> s.startsAt().equals(startsAt))
                    .findFirst()
                    .map(SlotResponse::endsAt)
                    .orElseThrow(() -> new ConflictException("That time slot is not available"));
        }
        int minutes = durationMinutes != null ? durationMinutes
                : settings.getInt("appointments.slot_minutes", 30);
        return startsAt.plus(Duration.ofMinutes(minutes));
    }

    private void enforcePatientCutoff(AuthenticatedUser user, Appointment a) {
        if (user.is(Role.PATIENT)) {
            int cutoff = settings.getInt("appointments.patient_cancel_cutoff_hours", 24);
            if (Instant.now().plus(Duration.ofHours(cutoff)).isAfter(a.getStartsAt())) {
                throw new BusinessRuleException("Appointments can only be changed online more than " + cutoff
                        + " hours in advance. Please contact the clinic.");
            }
        }
    }

    private Appointment requireAccessible(Long id) {
        AuthenticatedUser user = CurrentUser.require();
        Appointment a = appointments.findById(id).orElseThrow(() -> NotFoundException.of("Appointment", id));
        boolean allowed = switch (user.role()) {
            case PATIENT -> a.getPatientId().equals(user.patientId());
            case CLINICIAN -> a.getClinicianId().equals(user.id()) || access.isOnCareTeam(user.id(), a.getPatientId());
            case ADMIN -> true;
        };
        if (!allowed) {
            audit.denied("READ", "Appointment", id, a.getPatientId());
            if (user.is(Role.PATIENT)) {
                throw NotFoundException.of("Appointment", id);
            }
            throw new AccessDeniedException("Not permitted for this appointment");
        }
        return a;
    }

    private AppUser requireClinicianUser(Long clinicianId) {
        AppUser u = users.findById(clinicianId).orElseThrow(() -> NotFoundException.of("Clinician", clinicianId));
        if (u.getRole() != Role.CLINICIAN) {
            throw NotFoundException.of("Clinician", clinicianId);
        }
        return u;
    }

    private void requireBookableClinician(Long clinicianId) {
        AppUser u = requireClinicianUser(clinicianId);
        boolean accepting = profiles.findByUserId(clinicianId).map(p -> p.isAcceptingAppointments()).orElse(true);
        if (!u.isEnabled() || !accepting) {
            throw new BusinessRuleException("This clinician is not accepting appointments");
        }
    }

    private static void validateNoOverlap(List<AvailabilityWindowRequest> windows) {
        List<AvailabilityWindowRequest> sorted = new ArrayList<>(windows);
        sorted.sort((a, b) -> a.dayOfWeek() != b.dayOfWeek()
                ? a.dayOfWeek().compareTo(b.dayOfWeek()) : a.startTime().compareTo(b.startTime()));
        for (int i = 1; i < sorted.size(); i++) {
            var prev = sorted.get(i - 1);
            var cur = sorted.get(i);
            if (prev.dayOfWeek() == cur.dayOfWeek() && cur.startTime().isBefore(prev.endTime())) {
                throw new BusinessRuleException("Availability windows overlap on " + cur.dayOfWeek());
            }
        }
    }

    private Function<Appointment, AppointmentResponse> mapper(List<Appointment> list) {
        Map<Long, Patient> pts = patients.findAllById(list.stream().map(Appointment::getPatientId).distinct().toList())
                .stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        Map<Long, AppUser> cls = users.findAllById(list.stream().map(Appointment::getClinicianId).distinct().toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        return a -> {
            Patient p = pts.get(a.getPatientId());
            AppUser c = cls.get(a.getClinicianId());
            return new AppointmentResponse(a.getId(), a.getPatientId(), p == null ? null : p.fullName(),
                    p == null ? null : p.getMrn(), a.getClinicianId(), c == null ? null : c.getFullName(),
                    a.getStartsAt(), a.getEndsAt(), a.getStatus(), a.getType(), a.getReason(), a.getEncounterId(),
                    a.getRescheduledFromId(), a.getCancelledAt(), a.getCancellationReason(), a.getCreatedAt());
        };
    }

    private AppointmentResponse toResponse(Appointment a) {
        return mapper(List.of(a)).apply(a);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
