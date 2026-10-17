package com.rdss.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rdss.patients.Patient;
import com.rdss.support.IntegrationTest;
import com.rdss.support.TestFixtures;
import com.rdss.users.AppUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ReportsIT {

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    AppUser clinician;
    Patient mine;
    Patient notMine;

    @BeforeEach
    void setUp() {
        clinician = fx.clinician();
        mine = fx.patient();
        notMine = fx.patient();
        fx.assign(mine, clinician);
        diagnosis(mine, "J44.1");
        diagnosis(mine, "J44.1");
        diagnosis(notMine, "J44.1");
        diagnosis(notMine, "J45.909");
        appointment(mine, "COMPLETED", 1);
        appointment(mine, "NO_SHOW", 2);
        appointment(notMine, "COMPLETED", 3);
    }

    private void diagnosis(Patient p, String code) {
        jdbc.update("""
                INSERT INTO diagnosis (patient_id, icd10_code, code_version, description, diagnosis_type, status, recorded_by)
                SELECT ?, code, 'test', description, 'PRIMARY', 'ACTIVE', ? FROM icd10_code WHERE code = ?""",
                p.getId(), clinician.getId(), code);
    }

    private void appointment(Patient p, String status, int daysAgo) {
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(daysAgo, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO appointment (patient_id, clinician_id, starts_at, ends_at, status, appointment_type, created_by)
                VALUES (?, ?, ?, ?, ?, 'FOLLOW_UP', ?)""",
                p.getId(), clinician.getId(), java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(1800)),
                status, clinician.getId());
    }

    private JsonNode summary(String token) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/reports/summary").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void clinicianSeesOnlyCareTeamAggregatesAndNoFinance() throws Exception {
        JsonNode s = summary(fx.token(clinician));
        assertThat(s.get("scope").asText()).isEqualTo("CARE_TEAM");
        assertThat(s.get("patients").get("total").asLong()).isEqualTo(1);
        assertThat(s.get("topDiagnoses")).hasSize(1);
        assertThat(s.get("topDiagnoses").get(0).get("key").asText()).isEqualTo("J44.1");
        assertThat(s.get("topDiagnoses").get(0).get("count").asLong()).isEqualTo(2);
        assertThat(s.get("appointments").get("total").asLong()).isEqualTo(2);
        assertThat(s.get("appointments").get("completionRate").asDouble()).isEqualTo(0.5);
        assertThat(s.has("billing")).isFalse();
        assertThat(s.has("integration")).isFalse();
        // Aggregates only: nothing in the payload identifies a patient.
        String raw = s.toString();
        assertThat(raw).doesNotContain(mine.getMrn()).doesNotContain(mine.getLastName());
    }

    @Test
    void adminSeesOrganisationWideIncludingFinanceAndIntegration() throws Exception {
        JsonNode s = summary(fx.token(fx.admin()));
        assertThat(s.get("scope").asText()).isEqualTo("ORGANIZATION");
        assertThat(s.get("patients").get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(s.get("billing").get("currency").asText()).isNotBlank();
        assertThat(s.get("integration").has("hl7Received")).isTrue();
        long j44 = 0;
        for (JsonNode c : s.get("topDiagnoses")) {
            if (c.get("key").asText().equals("J44.1")) {
                j44 = c.get("count").asLong();
            }
        }
        assertThat(j44).isGreaterThanOrEqualTo(3);
    }

    @Test
    void patientsCannotUseReports() throws Exception {
        String token = fx.token(fx.patientWithAccount().account());
        mvc.perform(get("/api/v1/reports/summary").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reports/exports/diagnoses").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reports/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidPeriodsAreRejected() throws Exception {
        String token = fx.token(clinician);
        mvc.perform(get("/api/v1/reports/summary?from=2026-03-01&to=2026-02-01").header("Authorization", token))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/reports/summary?from=2020-01-01&to=2026-01-01").header("Authorization", token))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/reports/exports/nope").header("Authorization", token)).andExpect(status().isNotFound());
    }

    @Test
    void clinicianDiagnosisExportIsScopedAndAudited() throws Exception {
        var res = mvc.perform(get("/api/v1/reports/exports/diagnoses").header("Authorization", fx.token(clinician)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("rdss-diagnoses-")))
                .andReturn().getResponse();
        assertThat(res.getContentType()).startsWith("text/csv");
        String csv = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿icd10_code,description,");
        String[] lines = csv.trim().split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).startsWith("J44.1,").endsWith(",2,1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'REPORT_EXPORT' AND actor_user_id = ?",
                Long.class, clinician.getId())).isEqualTo(1);
    }

    @Test
    void financeAndIntegrationExportsAreAdminOnly() throws Exception {
        String token = fx.token(clinician);
        for (String ds : new String[] {"invoices", "revenue-by-service", "hl7-messages"}) {
            mvc.perform(get("/api/v1/reports/exports/" + ds).header("Authorization", token)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/reports/exports").header("Authorization", token))
                .andExpect(jsonPath("$[*].slug").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("invoices"))));
        mvc.perform(get("/api/v1/reports/exports").header("Authorization", fx.token(fx.admin())))
                .andExpect(jsonPath("$[*].slug").value(org.hamcrest.Matchers.hasItem("hl7-messages")));
    }

    @Test
    void hl7ExportNeutralisesSpreadsheetFormulasAndOmitsRawMessages() throws Exception {
        String control = "RPT" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO hl7_message (direction, transport, control_id, sending_application, sending_facility, message_type,
                                         raw_message, status, error_message)
                VALUES ('INBOUND', 'HTTP', ?, 'TESTAPP', 'TESTFAC', 'ADT^A01', 'MSH|^~\\&|SECRET-RAW-BODY', 'FAILED',
                        '=HYPERLINK("http://evil.example","x")')""", control);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String csv = mvc.perform(get("/api/v1/reports/exports/hl7-messages?from=" + today.minusDays(1) + "&to=" + today.plusDays(1))
                        .header("Authorization", fx.token(fx.admin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String line = csv.lines().filter(l -> l.contains(control)).findFirst().orElseThrow();
        assertThat(line).contains("\"'=HYPERLINK(\"\"http://evil.example\"\",\"\"x\"\")\"");
        assertThat(csv).doesNotContain("SECRET-RAW-BODY");
    }
}
