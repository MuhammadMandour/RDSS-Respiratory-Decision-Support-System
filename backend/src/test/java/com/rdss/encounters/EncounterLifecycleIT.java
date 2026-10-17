package com.rdss.encounters;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class EncounterLifecycleIT {

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired ObjectMapper json;

    AppUser clinician;
    PatientWithAccount pt;
    String token;

    @BeforeEach
    void setUp() {
        clinician = fx.clinician();
        pt = fx.patientWithAccount();
        fx.assign(pt.patient(), clinician);
        token = fx.token(clinician);
    }

    private JsonNode create() throws Exception {
        String body = mvc.perform(post("/api/v1/patients/{id}/encounters", pt.patient().getId())
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"OUTPATIENT\",\"chiefComplaint\":\"Cough for 3 weeks\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String updateBody(long version, String complaint) {
        return "{\"version\":" + version + ",\"type\":\"OUTPATIENT\",\"chiefComplaint\":\"" + complaint
                + "\",\"cough\":true,\"mmrcGrade\":2}";
    }

    @Test
    void finalizedEncounterIsImmutableAndAmendmentSupersedesIt() throws Exception {
        JsonNode e = create();
        long id = e.get("id").asLong();
        mvc.perform(put("/api/v1/encounters/{id}", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(e.get("version").asLong(), "Cough")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mmrcGrade").value(2));
        mvc.perform(post("/api/v1/encounters/{id}/finalize", id).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINALIZED"));

        // Editing or attaching data to a finalized encounter is refused.
        mvc.perform(put("/api/v1/encounters/{id}", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(1, "Changed")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/patients/{pid}/observations", pt.patient().getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"encounterId\":" + id + ",\"observedAt\":\"" + Instant.now().minusSeconds(5)
                                + "\",\"spo2\":97}"))
                .andExpect(status().isConflict());

        String amendBody = mvc.perform(post("/api/v1/encounters/{id}/amend", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Correct mMRC grade\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amendsEncounterId").value(id))
                .andExpect(jsonPath("$.chiefComplaint").value("Cough"))
                .andReturn().getResponse().getContentAsString();
        long amendmentId = json.readTree(amendBody).get("id").asLong();

        // A second amendment of the same original is refused.
        mvc.perform(post("/api/v1/encounters/{id}/amend", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/encounters/{id}/finalize", amendmentId).header("Authorization", token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/encounters/{id}", id).header("Authorization", token))
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.amendedByEncounterId").value(amendmentId));
    }

    @Test
    void staleVersionIsRejected() throws Exception {
        JsonNode e = create();
        long id = e.get("id").asLong();
        long v = e.get("version").asLong();
        mvc.perform(put("/api/v1/encounters/{id}", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(v, "First")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/encounters/{id}", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(v, "Second")))
                .andExpect(status().isConflict());
    }

    @Test
    void patientSeesOnlyFinalizedEncounters() throws Exception {
        JsonNode draft = create();
        JsonNode done = create();
        mvc.perform(post("/api/v1/encounters/{id}/finalize", done.get("id").asLong()).header("Authorization", token))
                .andExpect(status().isOk());
        String patientToken = fx.token(pt.account());
        mvc.perform(get("/api/v1/patients/{id}/encounters", pt.patient().getId()).header("Authorization", patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(done.get("id").asLong()));
        mvc.perform(get("/api/v1/encounters/{id}", draft.get("id").asLong()).header("Authorization", patientToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void respiratoryHistoryUpdatesCreateNewVersions() throws Exception {
        String url = "/api/v1/patients/" + pt.patient().getId() + "/respiratory-history";
        mvc.perform(get(url).header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asthma\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.versionNo").value(1));
        mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asthma\":true,\"copd\":true,\"targetSpo2Low\":88,\"targetSpo2High\":92}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.versionNo").value(2));
        mvc.perform(get(url + "/versions").header("Authorization", token))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].copd").value(false));
        mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSpo2Low\":95,\"targetSpo2High\":90}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void observationValidationAndEnteredInError() throws Exception {
        String url = "/api/v1/patients/" + pt.patient().getId() + "/observations";
        mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observedAt\":\"" + Instant.now().minusSeconds(5) + "\",\"spo2\":130}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("spo2"));
        String body = mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observedAt\":\"" + Instant.now().minusSeconds(5)
                                + "\",\"spo2\":93,\"respiratoryRate\":22}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long obsId = json.readTree(body).get("id").asLong();
        mvc.perform(post("/api/v1/observations/{id}/entered-in-error", obsId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong patient\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enteredInError").value(true))
                .andExpect(jsonPath("$.spo2").value(93));
    }
}
