package com.rdss.cdss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rdss.support.IntegrationTest;
import com.rdss.support.TestFixtures;
import com.rdss.support.TestFixtures.PatientWithAccount;
import com.rdss.users.AppUser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class CdssAssessmentIT {

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired ObjectMapper json;

    AppUser clinician;
    PatientWithAccount pt;
    String token;

    @BeforeEach
    void setUp() throws Exception {
        clinician = fx.clinician();
        pt = fx.patientWithAccount();
        fx.assign(pt.patient(), clinician);
        token = fx.token(clinician);
    }

    @AfterEach
    void resetRules() throws Exception {
        mvc.perform(put("/api/v1/cdss/rules/HYPOXAEMIA").header("Authorization", fx.token(fx.admin()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"));
    }

    private long observe(String body) throws Exception {
        String res = mvc.perform(post("/api/v1/patients/{id}/observations", pt.patient().getId())
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    private JsonNode assess(String body) throws Exception {
        String res = mvc.perform(post("/api/v1/patients/{id}/cdss/assessments", pt.patient().getId())
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private String vitals(Instant at, int rr, int spo2, int hr) {
        return "{\"observedAt\":\"" + at + "\",\"respiratoryRate\":" + rr + ",\"spo2\":" + spo2
                + ",\"onSupplementalOxygen\":false,\"systolicBp\":102,\"diastolicBp\":64,\"heartRate\":" + hr
                + ",\"temperatureC\":38.6,\"consciousness\":\"ALERT\"}";
    }

    @Test
    void assessmentStoresScoreAlertsAndProvenance() throws Exception {
        long obsId = observe(vitals(Instant.now().minusSeconds(120), 24, 92, 112));

        JsonNode a = assess("{}");

        assertThat(a.get("news2").get("score").asInt()).isEqualTo(8);
        assertThat(a.get("news2").get("risk").asText()).isEqualTo("HIGH");
        assertThat(a.get("highestSeverity").asText()).isEqualTo("CRITICAL");
        assertThat(a.get("engineVersion").asText()).isEqualTo("1.1.0");
        assertThat(a.get("disclaimer").asText()).contains("do not establish a diagnosis");
        // Provenance: exact inputs and rule versions are stored.
        assertThat(a.get("inputSnapshot").get("latestObservation").get("id").asLong()).isEqualTo(obsId);
        assertThat(a.get("rulesApplied").toString()).contains("NEWS2").contains("1.0.0");
        // Every alert explains itself and links guideline references with versions.
        JsonNode news2Alert = null;
        for (JsonNode alert : a.get("alerts")) {
            assertThat(alert.get("explanation").asText()).isNotBlank();
            if (alert.get("ruleCode").asText().equals("NEWS2")) news2Alert = alert;
        }
        assertThat(news2Alert).isNotNull();
        assertThat(news2Alert.get("guidelines").get(0).get("code").asText()).isEqualTo("RCP-NEWS2");
        assertThat(news2Alert.get("guidelines").get(0).get("version").asText()).contains("2017");
        assertThat(a.get("review").get("status").asText()).isEqualTo("PENDING");
        // No ML output unless a provider is explicitly enabled.
        assertThat(a.has("inference")).isFalse();
    }

    @Test
    void enteredInErrorObservationsAreExcluded() throws Exception {
        long bad = observe(vitals(Instant.now().minusSeconds(60), 30, 85, 140));
        mvc.perform(post("/api/v1/observations/{id}/entered-in-error", bad).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"wrong patient\"}"))
                .andExpect(status().isOk());

        JsonNode a = assess("{}");

        assertThat(a.has("news2")).isFalse();
        assertThat(a.get("missingData").toString()).contains("No observations recorded");
    }

    @Test
    void reviewRequiresDispositionOfEveryAlertAndReasonForDismissal() throws Exception {
        observe(vitals(Instant.now().minusSeconds(120), 24, 92, 112));
        JsonNode a = assess("{}");
        long id = a.get("id").asLong();
        JsonNode alerts = a.get("alerts");
        assertThat(alerts.size()).isGreaterThan(1);
        long first = alerts.get(0).get("id").asLong();

        // Incomplete review is rejected.
        mvc.perform(post("/api/v1/cdss/assessments/{id}/review", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alerts\":[{\"alertId\":" + first + ",\"disposition\":\"ACCEPTED\"}]}"))
                .andExpect(status().isUnprocessableEntity());

        // Dismissal without reason is rejected.
        StringBuilder all = new StringBuilder("[");
        for (int i = 0; i < alerts.size(); i++) {
            if (i > 0) all.append(',');
            all.append("{\"alertId\":").append(alerts.get(i).get("id").asLong()).append(",\"disposition\":\"DISMISSED\"}");
        }
        all.append(']');
        mvc.perform(post("/api/v1/cdss/assessments/{id}/review", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alerts\":" + all + "}"))
                .andExpect(status().isUnprocessableEntity());

        String accepted = all.toString().replace("\"DISMISSED\"}", "\"ACCEPTED\"}");
        mvc.perform(post("/api/v1/cdss/assessments/{id}/review", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Escalated to on-call\",\"alerts\":" + accepted + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.status").value("REVIEWED"))
                .andExpect(jsonPath("$.review.note").value("Escalated to on-call"))
                .andExpect(jsonPath("$.alerts[0].disposition").value("ACCEPTED"));

        // A completed review cannot be overwritten.
        mvc.perform(post("/api/v1/cdss/assessments/{id}/review", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alerts\":" + accepted + "}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/patients/{pid}/cdss/assessments", pt.patient().getId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].reviewStatus").value("REVIEWED"));
    }

    @Test
    void asthmaAnswersAreScoredAndStoredInSnapshot() throws Exception {
        JsonNode a = assess("{\"asthmaControl\":{\"daytimeSymptomsMoreThanTwiceWeekly\":true,"
                + "\"nightWakingDueToAsthma\":true,\"relieverNeededMoreThanTwiceWeekly\":true,\"activityLimitation\":false}}");
        assertThat(a.get("alerts").toString()).contains("uncontrolled (GINA 3/4)");
        assertThat(a.get("inputSnapshot").get("asthmaControl").get("nightWakingDueToAsthma").asBoolean()).isTrue();
    }

    @Test
    void encounterMustBelongToPatient() throws Exception {
        var other = fx.patient();
        fx.assign(other, clinician);
        String enc = mvc.perform(post("/api/v1/patients/{id}/encounters", other.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"OUTPATIENT\"}"))
                .andReturn().getResponse().getContentAsString();
        long encId = json.readTree(enc).get("id").asLong();
        mvc.perform(post("/api/v1/patients/{id}/cdss/assessments", pt.patient().getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"encounterId\":" + encId + "}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void disabledRuleDoesNotRunAndIsRecorded() throws Exception {
        mvc.perform(put("/api/v1/cdss/rules/HYPOXAEMIA").header("Authorization", fx.token(fx.admin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        observe(vitals(Instant.now().minusSeconds(120), 18, 88, 80));
        JsonNode a = assess("{}");
        assertThat(a.get("alerts").toString()).doesNotContain("HYPOXAEMIA");
        assertThat(a.get("rulesApplied").toString()).contains("DISABLED");
    }

    @Test
    void ruleParametersAreValidated() throws Exception {
        String admin = fx.token(fx.admin());
        mvc.perform(put("/api/v1/cdss/rules/OBS_TREND").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"parameters\":{\"unknown\":1}}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/cdss/rules/NEWS2").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void patientsAndOtherCliniciansCannotUseCdss() throws Exception {
        observe(vitals(Instant.now().minusSeconds(120), 18, 97, 80));
        long id = assess("{}").get("id").asLong();
        mvc.perform(get("/api/v1/cdss/assessments/{id}", id).header("Authorization", fx.token(pt.account())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/patients/{pid}/cdss/assessments", pt.patient().getId())
                        .header("Authorization", fx.token(pt.account())).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/cdss/assessments/{id}", id).header("Authorization", fx.token(fx.clinician())))
                .andExpect(status().isForbidden());
    }
}
