package com.rdss.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rdss.audit.AuditLogRepository;
import com.rdss.common.security.Role;
import com.rdss.patients.PatientRepository;
import com.rdss.support.IntegrationTest;
import com.rdss.support.TestFixtures;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AuthIT {

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired PatientRepository patients;
    @Autowired AuditLogRepository auditLogs;

    @Test
    void loginReturnsTokenThatAuthenticatesRequests() throws Exception {
        AppUser clinician = fx.clinician();

        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + clinician.getUsername().toUpperCase() + "\",\"password\":\""
                                + TestFixtures.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.role").value("CLINICIAN"))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).get("accessToken").asText();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(clinician.getId()));
    }

    @Test
    void wrongPasswordAndUnknownUserGiveSameGenericError() throws Exception {
        AppUser clinician = fx.clinician();
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + clinician.getUsername() + "\",\"password\":\"wrong-password-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody-here\",\"password\":\"wrong-password-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void accountLocksAfterRepeatedFailures() throws Exception {
        AppUser clinician = fx.clinician();
        String bad = "{\"username\":\"" + clinician.getUsername() + "\",\"password\":\"wrong-password-1\"}";
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isUnauthorized());
        }
        // Even the correct password is refused while locked.
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + clinician.getUsername() + "\",\"password\":\""
                                + TestFixtures.PASSWORD + "\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void disabledUserTokenIsRejectedImmediately() throws Exception {
        AppUser clinician = fx.clinician();
        String token = fx.token(clinician);
        clinician.setEnabled(false);
        users.save(clinician);

        mvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedOrMalformedTokensAreRejected() throws Exception {
        String token = fx.token(fx.clinician());
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        mvc.perform(get("/api/v1/auth/me").header("Authorization", tampered)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientSelfRegistrationCreatesLinkedPatientRecord() throws Exception {
        String username = "selfreg" + System.nanoTime();
        String body = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@example.test","password":"Str0ng-Password",
                                 "firstName":"Ada","lastName":"Synthetic","dateOfBirth":"1990-05-01","sex":"FEMALE"}
                                """.formatted(username, username)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("PATIENT"))
                .andReturn().getResponse().getContentAsString();
        JsonNode user = json.readTree(body).get("user");
        long patientId = user.get("patientId").asLong();

        assertThat(patients.findById(patientId)).hasValueSatisfying(p -> {
            assertThat(p.getUserId()).isEqualTo(user.get("id").asLong());
            assertThat(p.getMrn()).startsWith("MRN");
        });
        // Registration never stores the raw password.
        assertThat(users.findById(user.get("id").asLong()).orElseThrow().getPasswordHash())
                .startsWith("$2").doesNotContain("Str0ng-Password");
    }

    @Test
    void registrationRejectsWeakPasswordAndDuplicateUsername() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"weakpw%d","email":"weak%d@example.test","password":"short",
                                 "firstName":"A","lastName":"B","dateOfBirth":"1990-05-01","sex":"MALE"}
                                """.formatted(System.nanoTime(), System.nanoTime())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));

        AppUser existing = fx.user(Role.PATIENT, "dup");
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"other%d@example.test","password":"Str0ng-Password",
                                 "firstName":"A","lastName":"B","dateOfBirth":"1990-05-01","sex":"MALE"}
                                """.formatted(existing.getUsername(), System.nanoTime())))
                .andExpect(status().isConflict());
    }

    @Test
    void roleRestrictedEndpointsReturn403ForOtherRoles() throws Exception {
        String clinicianToken = fx.token(fx.clinician());
        mvc.perform(get("/api/v1/users").header("Authorization", clinicianToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit").header("Authorization", clinicianToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users").header("Authorization", fx.token(fx.admin()))).andExpect(status().isOk());
    }

    @Test
    void failedLoginIsAudited() throws Exception {
        long before = auditLogs.count();
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"audit-probe\",\"password\":\"wrong-password-1\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(auditLogs.count()).isGreaterThan(before);
    }
}
