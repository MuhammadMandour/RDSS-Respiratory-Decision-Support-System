package com.rdss.appointments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientCareTeamRepository;
import com.rdss.patients.PatientCareTeamMember;
import com.rdss.support.IntegrationTest;
import com.rdss.support.TestFixtures;
import com.rdss.support.TestFixtures.PatientWithAccount;
import com.rdss.users.AppUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AppointmentConflictIT {

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired ObjectMapper json;
    @Autowired AppointmentRepository appointments;
    @Autowired PatientCareTeamRepository careTeam;

    AppUser clinician;
    Patient p1;
    Patient p2;
    String clinicianToken;
    /** A clinic-time (UTC in tests) slot boundary three days ahead. */
    Instant slot;

    @BeforeEach
    void setUp() {
        clinician = fx.clinician();
        fx.fullWeekAvailability(clinician);
        p1 = fx.patient();
        p2 = fx.patient();
        fx.assign(p1, clinician);
        fx.assign(p2, clinician);
        clinicianToken = fx.token(clinician);
        slot = LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(10, 0).toInstant(ZoneOffset.UTC);
    }

    private ResultActions book(String token, Long patientId, Instant startsAt, Integer minutes) throws Exception {
        String body = "{\"patientId\":" + patientId + ",\"clinicianId\":" + clinician.getId() + ",\"startsAt\":\""
                + startsAt + "\",\"type\":\"CONSULTATION\"" + (minutes == null ? "" : ",\"durationMinutes\":" + minutes)
                + "}";
        return mvc.perform(post("/api/v1/appointments").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void clinicianCannotBeDoubleBooked() throws Exception {
        book(clinicianToken, p1.getId(), slot, 30).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
        // Same time, different patient.
        book(clinicianToken, p2.getId(), slot, 30).andExpect(status().isConflict());
        // Partially overlapping.
        book(clinicianToken, p2.getId(), slot.plus(Duration.ofMinutes(15)), 30).andExpect(status().isConflict());
        // Back-to-back is fine (half-open intervals).
        book(clinicianToken, p2.getId(), slot.plus(Duration.ofMinutes(30)), 30).andExpect(status().isCreated());
    }

    @Test
    void patientCannotHaveOverlappingAppointments() throws Exception {
        AppUser second = fx.clinician();
        fx.assign(p1, second);
        book(clinicianToken, p1.getId(), slot, 60).andExpect(status().isCreated());
        String body = "{\"patientId\":" + p1.getId() + ",\"clinicianId\":" + second.getId() + ",\"startsAt\":\""
                + slot.plus(Duration.ofMinutes(30)) + "\",\"type\":\"CONSULTATION\",\"durationMinutes\":30}";
        mvc.perform(post("/api/v1/appointments").header("Authorization", fx.token(second))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void databaseExclusionConstraintBlocksOverlapEvenWithoutServiceChecks() {
        Instant start = slot.plus(Duration.ofDays(1));
        appointments.saveAndFlush(new Appointment(p1.getId(), clinician.getId(), start, start.plusSeconds(1800),
                Appointment.Status.SCHEDULED, Appointment.Type.CONSULTATION, null, clinician.getId(), null));
        assertThatThrownBy(() -> appointments.saveAndFlush(new Appointment(p2.getId(), clinician.getId(),
                start.plusSeconds(600), start.plusSeconds(2400), Appointment.Status.REQUESTED,
                Appointment.Type.CONSULTATION, null, clinician.getId(), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cancelledAppointmentFreesTheSlot() throws Exception {
        String body = book(clinicianToken, p1.getId(), slot, 30).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        mvc.perform(post("/api/v1/appointments/{id}/cancel", id).header("Authorization", clinicianToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Clinic closed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        book(clinicianToken, p2.getId(), slot, 30).andExpect(status().isCreated());
    }

    @Test
    void rescheduleToOverlappingTimeOfSameAppointmentSucceeds() throws Exception {
        String body = book(clinicianToken, p1.getId(), slot, 60).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        mvc.perform(post("/api/v1/appointments/{id}/reschedule", id).header("Authorization", clinicianToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\":\"" + slot.plus(Duration.ofMinutes(30)) + "\",\"durationMinutes\":60}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rescheduledFromId").value(id));
        assertThat(appointments.findById(id)).hasValueSatisfying(a ->
                assertThat(a.getStatus()).isEqualTo(Appointment.Status.CANCELLED));
    }

    @Test
    void patientBookingMustUseOfferedSlotAndAddsClinicianToCareTeam() throws Exception {
        PatientWithAccount self = fx.patientWithAccount();
        AppUser newClinician = fx.clinician();
        fx.fullWeekAvailability(newClinician);
        String token = fx.token(self.account());
        String misaligned = "{\"clinicianId\":" + newClinician.getId() + ",\"startsAt\":\""
                + slot.plus(Duration.ofMinutes(7)) + "\",\"type\":\"CONSULTATION\"}";
        mvc.perform(post("/api/v1/appointments").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(misaligned))
                .andExpect(status().isConflict());

        // The patientId in the body is ignored for patients: they always book for themselves.
        String ok = "{\"patientId\":" + p1.getId() + ",\"clinicianId\":" + newClinician.getId() + ",\"startsAt\":\""
                + slot + "\",\"type\":\"CONSULTATION\"}";
        mvc.perform(post("/api/v1/appointments").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientId").value(self.patient().getId()))
                .andExpect(jsonPath("$.status").value("REQUESTED"));
        assertThat(careTeam.existsById(new PatientCareTeamMember.Key(self.patient().getId(), newClinician.getId())))
                .isTrue();

        // The booked slot is no longer offered.
        LocalDate day = slot.atZone(ZoneOffset.UTC).toLocalDate();
        mvc.perform(get("/api/v1/clinicians/{id}/slots", newClinician.getId()).header("Authorization", token)
                        .param("from", day.toString()).param("to", day.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.startsAt=='" + slot + "')]").isEmpty());
    }

    @Test
    void patientCannotCancelInsideCutoffWindow() throws Exception {
        PatientWithAccount self = fx.patientWithAccount();
        Instant soon = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES);
        var a = appointments.saveAndFlush(new Appointment(self.patient().getId(), clinician.getId(), soon,
                soon.plusSeconds(1800), Appointment.Status.SCHEDULED, Appointment.Type.CONSULTATION, null,
                clinician.getId(), null));
        mvc.perform(post("/api/v1/appointments/{id}/cancel", a.getId())
                        .header("Authorization", fx.token(self.account()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Busy\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void patientCannotSeeOrCancelAnotherPatientsAppointment() throws Exception {
        String body = book(clinicianToken, p1.getId(), slot, 30).andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        String intruder = fx.token(fx.patientWithAccount().account());
        mvc.perform(get("/api/v1/appointments/{id}", id).header("Authorization", intruder))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/appointments/{id}/cancel", id).header("Authorization", intruder)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/appointments").param("patientId", String.valueOf(p1.getId()))
                        .header("Authorization", intruder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void pastStartIsRejected() throws Exception {
        book(clinicianToken, p1.getId(), Instant.now().minus(Duration.ofHours(1)), 30)
                .andExpect(status().isUnprocessableEntity());
    }
}
