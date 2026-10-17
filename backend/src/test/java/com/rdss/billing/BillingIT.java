package com.rdss.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rdss.diagnoses.Icd10ImportParserTest;
import com.rdss.support.IntegrationTest;
import com.rdss.support.TestFixtures;
import com.rdss.support.TestFixtures.PatientWithAccount;
import com.rdss.users.AppUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class BillingIT {

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired ObjectMapper json;
    @Autowired BillableServiceRepository catalogue;

    AppUser admin;
    String adminToken;
    PatientWithAccount pt;
    long consultId;

    @BeforeEach
    void setUp() {
        admin = fx.admin();
        adminToken = fx.token(admin);
        pt = fx.patientWithAccount();
        consultId = catalogue.findByCode("CONS-NEW").orElseThrow().getId();
    }

    private JsonNode createDraft(String body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/invoices").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String draftBody() {
        return """
                {"patientId":%d,"diagnosisCodes":["J44.1","R06.02"],"lines":[
                  {"serviceId":%d,"quantity":1},
                  {"description":"Spacer device","unitPrice":18.50,"quantity":3,"discountPercent":10,"taxable":false}
                ]}""".formatted(pt.patient().getId(), consultId);
    }

    @Test
    void fullLifecycleWithPartialPaymentsAndReversal() throws Exception {
        JsonNode draft = createDraft(draftBody());
        long id = draft.get("summary").get("id").asLong();
        assertThat(draft.get("summary").get("invoiceNumber").asText()).matches("INV-\\d{4}-\\d{6}");
        assertThat(draft.get("summary").get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("subtotal").decimalValue()).isEqualByComparingTo("235.50");
        assertThat(draft.get("discountTotal").decimalValue()).isEqualByComparingTo("5.55");
        assertThat(draft.get("summary").get("total").decimalValue()).isEqualByComparingTo("229.95");
        assertThat(draft.get("diagnoses").get(0).get("code").asText()).isEqualTo("J44.1");
        assertThat(draft.get("diagnoses").get(1).get("sequence").asInt()).isEqualTo(2);

        // Patients cannot see drafts.
        mvc.perform(get("/api/v1/invoices/{id}", id).header("Authorization", fx.token(pt.account())))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/invoices/{id}/issue", id).header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.status").value("ISSUED"))
                .andExpect(jsonPath("$.summary.dueDate").exists());
        // Issued invoices are frozen.
        mvc.perform(put("/api/v1/invoices/{id}", id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(draftBody().replace("{\"patientId\"", "{\"version\":1,\"patientId\"")))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/invoices/{id}/payments", id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00,\"method\":\"CARD\",\"reference\":\"POS-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.summary.outstanding").value(129.95));
        mvc.perform(post("/api/v1/invoices/{id}/payments", id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":200.00,\"method\":\"CASH\"}"))
                .andExpect(status().isUnprocessableEntity());
        // Payments cannot be recorded as SIMULATED through the staff endpoint.
        mvc.perform(post("/api/v1/invoices/{id}/payments", id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1.00,\"method\":\"SIMULATED\"}"))
                .andExpect(status().isBadRequest());

        String afterPay = mvc.perform(post("/api/v1/invoices/{id}/payments", id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":129.95,\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(jsonPath("$.summary.status").value("PAID"))
                .andReturn().getResponse().getContentAsString();
        long lastPayment = json.readTree(afterPay).get("payments").get(1).get("id").asLong();

        mvc.perform(post("/api/v1/invoices/{id}/void", id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/payments/{pid}/reverse", lastPayment).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Bank returned transfer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.payments[1].status").value("REVERSED"));
    }

    @Test
    void patientSeesOwnIssuedInvoicesAndCanOnlySimulatePayment() throws Exception {
        long id = createDraft(draftBody()).get("summary").get("id").asLong();
        mvc.perform(post("/api/v1/invoices/{id}/issue", id).header("Authorization", adminToken)).andExpect(status().isOk());
        String patientToken = fx.token(pt.account());

        mvc.perform(get("/api/v1/invoices").header("Authorization", patientToken))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
        mvc.perform(post("/api/v1/invoices/{id}/payments", id).header("Authorization", patientToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00,\"method\":\"CARD\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/invoices/{id}/simulated-payment", id).header("Authorization", patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.startsWith("SIMULATED PAYMENT")))
                .andExpect(jsonPath("$.invoice.summary.status").value("PAID"))
                .andExpect(jsonPath("$.invoice.payments[0].simulated").value(true))
                .andExpect(jsonPath("$.invoice.payments[0].method").value("SIMULATED"));
        mvc.perform(post("/api/v1/invoices/{id}/simulated-payment", id).header("Authorization", patientToken))
                .andExpect(status().isConflict());

        // Another patient can neither see nor pay it.
        String intruder = fx.token(fx.patientWithAccount().account());
        mvc.perform(get("/api/v1/invoices/{id}", id).header("Authorization", intruder)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/invoices/{id}/simulated-payment", id).header("Authorization", intruder))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/invoices").param("patientId", String.valueOf(pt.patient().getId()))
                        .header("Authorization", intruder))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void diagnosisCodesMustBeValidUsableAndUnique() throws Exception {
        String base = "{\"patientId\":%d,\"lines\":[{\"serviceId\":%d,\"quantity\":1}],\"diagnosisCodes\":%s}";
        mvc.perform(post("/api/v1/invoices").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(base.formatted(pt.patient().getId(), consultId, "[\"not-a-code\"]")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/invoices").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(base.formatted(pt.patient().getId(), consultId, "[\"J99.9\"]")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/invoices").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(base.formatted(pt.patient().getId(), consultId, "[\"J44.1\",\"J44.1\"]")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/invoices").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":%d,\"lines\":[{\"quantity\":1}]}".formatted(pt.patient().getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clinicianNeedsCareTeamAccessAndCannotRecordPayments() throws Exception {
        AppUser clinician = fx.clinician();
        String token = fx.token(clinician);
        mvc.perform(post("/api/v1/invoices").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody()))
                .andExpect(status().isForbidden());
        fx.assign(pt.patient(), clinician);
        long id = json.readTree(mvc.perform(post("/api/v1/invoices").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(draftBody()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("summary").get("id").asLong();
        mvc.perform(post("/api/v1/invoices/{id}/issue", id).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/invoices/{id}/payments", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00,\"method\":\"CASH\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void catalogueIsAdminManagedAndPriceChangesDoNotAlterExistingInvoices() throws Exception {
        long id = createDraft(draftBody()).get("summary").get("id").asLong();
        mvc.perform(put("/api/v1/billing/services/{id}", consultId).header("Authorization", fx.token(fx.clinician()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CONS-NEW\",\"name\":\"x\",\"category\":\"CONSULTATION\",\"unitPrice\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/billing/services/{id}", consultId).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CONS-NEW\",\"name\":\"New patient respiratory consultation\",\"category\":\"CONSULTATION\",\"unitPrice\":999.00}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/invoices/{id}", id).header("Authorization", adminToken))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(180.00));
        // Restore for other tests.
        mvc.perform(put("/api/v1/billing/services/{id}", consultId).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"CONS-NEW\",\"name\":\"New patient respiratory consultation\",\"category\":\"CONSULTATION\",\"unitPrice\":180.00}"));
    }

    @Test
    void icd10SearchAndAdminImport() throws Exception {
        String token = fx.token(fx.clinician());
        mvc.perform(get("/api/v1/icd10").param("q", "asthma").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].code").value(org.hamcrest.Matchers.startsWith("J45")));
        mvc.perform(get("/api/v1/icd10").param("q", "J44").header("Authorization", token))
                .andExpect(jsonPath("$.content[*].code", org.hamcrest.Matchers.hasItem("J44.1")));

        String file = Icd10ImportParserTest.line(1, "J988", 0, "Other specified respiratory disorders", "Other specified respiratory disorders")
                + "\n" + Icd10ImportParserTest.line(2, "J9883", 1, "Other specified respiratory disorders test",
                "Synthetic test row for importer");
        var upload = new MockMultipartFile("file", "order.txt", "text/plain", file.getBytes());
        mvc.perform(multipart("/api/v1/icd10/import").file(upload).param("codeVersion", "TEST").header("Authorization", token))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/icd10/import").file(upload).param("codeVersion", "TEST").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.billable").value(1));
        mvc.perform(get("/api/v1/icd10/J98.83").header("Authorization", token))
                .andExpect(jsonPath("$.source").value("OFFICIAL_IMPORT"))
                .andExpect(jsonPath("$.codeVersion").value("TEST"));
    }

    @Test
    void diagnosesSnapshotCodeAndRejectHeadersAndDisabledCodes() throws Exception {
        AppUser clinician = fx.clinician();
        fx.assign(pt.patient(), clinician);
        String token = fx.token(clinician);
        String url = "/api/v1/patients/" + pt.patient().getId() + "/diagnoses";
        mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"J44.1\",\"type\":\"PRIMARY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Chronic obstructive pulmonary disease with (acute) exacerbation"))
                .andExpect(jsonPath("$.codeVersion").value("FY2025"));
        mvc.perform(put("/api/v1/icd10/R05.9/active").param("active", "false").header("Authorization", adminToken))
                .andExpect(status().isOk());
        mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"R05.9\",\"type\":\"SECONDARY\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/icd10/R05.9/active").param("active", "true").header("Authorization", adminToken));
        mvc.perform(get(url).header("Authorization", fx.token(pt.account())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(url).header("Authorization", adminToken)).andExpect(status().isForbidden());
    }
}
