package com.rdss.patients;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.rdss.audit.AuditLog;
import com.rdss.audit.AuditLogRepository;
import com.rdss.support.IntegrationTest;
import com.rdss.support.TestFixtures;
import com.rdss.support.TestFixtures.PatientWithAccount;
import com.rdss.users.AppUser;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Security boundary tests: identifiers in URLs are never trusted.
 */
@IntegrationTest
class PatientDataIsolationIT {

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired AuditLogRepository auditLogs;

    PatientWithAccount alice;
    PatientWithAccount bob;
    AppUser clinician;
    AppUser otherClinician;

    @BeforeEach
    void setUp() throws Exception {
        alice = fx.patientWithAccount();
        bob = fx.patientWithAccount();
        clinician = fx.clinician();
        otherClinician = fx.clinician();
        fx.assign(alice.patient(), clinician);
        fx.assign(bob.patient(), clinician);
        // Clinical data for Bob.
        mvc.perform(post("/api/v1/patients/{id}/observations", bob.patient().getId())
                        .header("Authorization", fx.token(clinician)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observedAt\":\"" + Instant.now().minusSeconds(60) + "\",\"spo2\":95}"))
                .andExpect(status().isCreated());
    }

    @Test
    void patientCanReadOwnRecord() throws Exception {
        String token = fx.token(alice.account());
        mvc.perform(get("/api/v1/patients/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.patient().getId()));
        mvc.perform(get("/api/v1/patients/{id}", alice.patient().getId()).header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void patientCannotReadAnotherPatientByChangingTheId() throws Exception {
        String token = fx.token(alice.account());
        long bobId = bob.patient().getId();
        mvc.perform(get("/api/v1/patients/{id}", bobId).header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/patients/{id}/observations", bobId).header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/patients/{id}/encounters", bobId).header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/patients/{id}/prescriptions", bobId).header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/patients/{id}/respiratory-history", bobId).header("Authorization", token))
                .andExpect(status().isNotFound());
        // Response is identical for a non-existent id, so existence is not leaked.
        mvc.perform(get("/api/v1/patients/{id}", 999_999_999L).header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void deniedAccessIsAudited() throws Exception {
        mvc.perform(get("/api/v1/patients/{id}", bob.patient().getId())
                        .header("Authorization", fx.token(alice.account())))
                .andExpect(status().isNotFound());
        Assertions.assertThat(auditLogs.findAll()).anySatisfy(a -> {
            Assertions.assertThat(a.getOutcome()).isEqualTo(AuditLog.Outcome.DENIED);
            Assertions.assertThat(a.getActorUserId()).isEqualTo(alice.account().getId());
            Assertions.assertThat(a.getPatientId()).isEqualTo(bob.patient().getId());
        });
    }

    @Test
    void patientCannotSearchDirectoryOrWriteClinicalData() throws Exception {
        String token = fx.token(alice.account());
        mvc.perform(get("/api/v1/patients").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/patients/{id}/observations", alice.patient().getId())
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observedAt\":\"" + Instant.now().minusSeconds(60) + "\",\"spo2\":99}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void patientMayUpdateOwnContactDetailsOnly() throws Exception {
        String token = fx.token(alice.account());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/patients/{id}/contact", alice.patient().getId())
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+1 555 0111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+1 555 0111"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/patients/{id}/contact", bob.patient().getId())
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+1 555 0111\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/patients/{id}", alice.patient().getId())
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"X\",\"lastName\":\"Y\",\"dateOfBirth\":\"1980-01-01\",\"sex\":\"MALE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void clinicianOnlySeesCareTeamPatients() throws Exception {
        mvc.perform(get("/api/v1/patients/{id}/observations", bob.patient().getId())
                        .header("Authorization", fx.token(clinician)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].spo2").value(95));

        String other = fx.token(otherClinician);
        mvc.perform(get("/api/v1/patients/{id}", bob.patient().getId()).header("Authorization", other))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/patients/{id}/observations", bob.patient().getId()).header("Authorization", other))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/patients").header("Authorization", other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", everyItem(not(bob.patient().getId().intValue()))));
        mvc.perform(get("/api/v1/patients").header("Authorization", fx.token(clinician)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(bob.patient().getId().intValue())));
    }

    @Test
    void adminSeesDemographicsButNoClinicalContent() throws Exception {
        String admin = fx.token(fx.admin());
        mvc.perform(get("/api/v1/patients/{id}", bob.patient().getId()).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mrn").value(bob.patient().getMrn()))
                .andExpect(jsonPath("$.allergies").doesNotExist())
                .andExpect(jsonPath("$.medicalHistory").doesNotExist());
        mvc.perform(get("/api/v1/patients/{id}/observations", bob.patient().getId()).header("Authorization", admin))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/patients/{id}", bob.patient().getId())).andExpect(status().isUnauthorized());
    }
}
