package com.rdss.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rdss.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SystemStatusIT {

    @Autowired
    MockMvc mvc;

    @Test
    void statusIsPublicAndReportsMigratedDatabase() throws Exception {
        mvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("RDSS"))
                .andExpect(jsonPath("$.dependencies[?(@.name=='database')].status").value("UP"))
                .andExpect(jsonPath("$.dependencies[?(@.name=='database')].detail").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("schema v"))))
                // Orthanc is not running in tests, so overall status must degrade rather than fail.
                .andExpect(jsonPath("$.dependencies[?(@.name=='imaging')].status").value("DOWN"))
                .andExpect(jsonPath("$.status").value("DEGRADED"));
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unknownApiRequiresAuthenticationWithJsonError() throws Exception {
        mvc.perform(get("/api/v1/patients"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }
}
