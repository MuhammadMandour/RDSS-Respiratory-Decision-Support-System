package com.rdss.imaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rdss.patients.Patient;
import com.rdss.support.IntegrationTest;
import com.rdss.support.StubImagingArchive;
import com.rdss.support.TestFixtures;
import com.rdss.support.TestFixtures.PatientWithAccount;
import com.rdss.users.AppUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ImagingIT {

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired ObjectMapper json;
    @Autowired StubImagingArchive.Stub archive;

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

    static String uid() {
        return "2.25." + Math.abs(UUID.randomUUID().getMostSignificantBits());
    }

    private ResultActions upload(Patient p, String auth, byte[]... files) throws Exception {
        var req = multipart("/api/v1/patients/{id}/imaging-studies/upload", p.getId());
        int i = 0;
        for (byte[] f : files) {
            req.file(new MockMultipartFile("files", "f" + (i++) + ".dcm", "application/dicom", f));
        }
        return mvc.perform(req.header("Authorization", auth));
    }

    private long uploadStudy(Patient p, String studyUid, String patientIdTag) throws Exception {
        String series = uid();
        String body = upload(p, token,
                StubImagingArchive.dicom(studyUid, series, uid(), patientIdTag, "CT"),
                StubImagingArchive.dicom(studyUid, series, uid(), patientIdTag, "CT"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("studies").get(0).get("id").asLong();
    }

    @Test
    void uploadLinksStudyWithSeriesMetadataAndFlagsPatientIdMismatch() throws Exception {
        String studyUid = uid();
        String series = uid();
        upload(pt.patient(), token,
                StubImagingArchive.dicom(studyUid, series, uid(), "SOMEONE-ELSE", "CT"),
                StubImagingArchive.dicom(studyUid, series, uid(), "SOMEONE-ELSE", "CT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instancesStored").value(2))
                .andExpect(jsonPath("$.studies[0].studyInstanceUid").value(studyUid))
                .andExpect(jsonPath("$.studies[0].numberOfInstances").value(2))
                .andExpect(jsonPath("$.studies[0].patientIdMismatch").value(true))
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("differs from MRN")));

        mvc.perform(get("/api/v1/patients/{id}/imaging-studies", pt.patient().getId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modalities").value("CT"));
    }

    @Test
    void nonDicomUploadIsRejected() throws Exception {
        upload(pt.patient(), token, "not dicom".getBytes()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void studyLinkedToAnotherPatientCannotReceiveInstances() throws Exception {
        String studyUid = uid();
        uploadStudy(pt.patient(), studyUid, pt.patient().getMrn());
        Patient other = fx.patient();
        fx.assign(other, clinician);
        String sop = uid();

        upload(other, token, StubImagingArchive.dicom(studyUid, uid(), sop, other.getMrn(), "CT"))
                .andExpect(status().isConflict());
        // The newly stored instance was removed from the archive again.
        assertThat(archive.deleted).contains("orthanc-instance-" + sop);
    }

    @Test
    void linkingExistingArchiveStudy() throws Exception {
        String studyUid = uid();
        archive.storeInstance(StubImagingArchive.dicom(studyUid, uid(), uid(), pt.patient().getMrn(), "DX"));
        mvc.perform(post("/api/v1/patients/{id}/imaging-studies/link", pt.patient().getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"studyInstanceUid\":\"" + studyUid + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.study.source").value("LINKED"))
                .andExpect(jsonPath("$.study.patientIdMismatch").value(false));
        mvc.perform(post("/api/v1/patients/{id}/imaging-studies/link", pt.patient().getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"studyInstanceUid\":\"9.9.9.9\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void studiesAreIsolatedByPatient() throws Exception {
        long studyId = uploadStudy(pt.patient(), uid(), pt.patient().getMrn());
        // Owner patient can see it; another patient and a non-care-team clinician cannot.
        mvc.perform(get("/api/v1/imaging-studies/{id}", studyId).header("Authorization", fx.token(pt.account())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/imaging-studies/{id}", studyId).header("Authorization", fx.token(fx.patientWithAccount().account())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/imaging-studies/{id}", studyId).header("Authorization", fx.token(fx.clinician())))
                .andExpect(status().isForbidden());
        // Patients cannot upload.
        upload(pt.patient(), fx.token(pt.account()), StubImagingArchive.dicom(uid(), uid(), uid(), "X", "CT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerSessionSetsScopedCookieAndProxyFailsClosed() throws Exception {
        String studyUid = uid();
        long studyId = uploadStudy(pt.patient(), studyUid, pt.patient().getMrn());
        String otherUid = uid();
        Patient other = fx.patient();
        fx.assign(other, clinician);
        uploadStudy(other, otherUid, other.getMrn());

        var res = mvc.perform(post("/api/v1/imaging/viewer-sessions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"studyIds\":[" + studyId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerUrl").value("/ohif/viewer?StudyInstanceUIDs=" + studyUid))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("RDSS_VIEWER="),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/dicomweb"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"))))
                .andReturn().getResponse();
        Cookie viewer = res.getCookie("RDSS_VIEWER");
        assertThat(viewer).isNotNull();

        // No cookie -> 401; a bearer token is not accepted on the proxy.
        mvc.perform(get("/api/v1/dicomweb/studies/{uid}/metadata", studyUid)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/dicomweb/studies/{uid}/metadata", studyUid).header("Authorization", token))
                .andExpect(status().isUnauthorized());
        // Granted study passes authorization (upstream is not running in tests -> 502).
        mvc.perform(get("/api/v1/dicomweb/studies/{uid}/metadata", studyUid).cookie(viewer))
                .andExpect(status().isBadGateway());
        // Another patient's study, and listing all studies, are refused.
        mvc.perform(get("/api/v1/dicomweb/studies/{uid}/metadata", otherUid).cookie(viewer)).andExpect(status().isForbidden());
        // An unfiltered study query is narrowed to the grant and forwarded (upstream absent -> 502), not refused.
        mvc.perform(get("/api/v1/dicomweb/studies").cookie(viewer)).andExpect(status().isBadGateway());
        mvc.perform(get("/api/v1/dicomweb/studies").param("0020000D", otherUid).cookie(viewer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dicomweb/studies").param("StudyInstanceUID", otherUid).cookie(viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerSessionRequiresAccessToEveryStudy() throws Exception {
        long mine = uploadStudy(pt.patient(), uid(), pt.patient().getMrn());
        Patient other = fx.patient();
        fx.assign(other, clinician);
        long theirs = uploadStudy(other, uid(), other.getMrn());
        // Different patients in one session are refused even for a clinician who can see both.
        mvc.perform(post("/api/v1/imaging/viewer-sessions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"studyIds\":[" + mine + "," + theirs + "]}"))
                .andExpect(status().isUnprocessableEntity());
        // A patient cannot open another patient's study.
        mvc.perform(post("/api/v1/imaging/viewer-sessions").header("Authorization", fx.token(pt.account()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"studyIds\":[" + theirs + "]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void findingsAreRecordedValidatedAndUsedByCdss() throws Exception {
        long studyId = uploadStudy(pt.patient(), uid(), pt.patient().getMrn());
        mvc.perform(post("/api/v1/imaging-studies/{id}/findings", studyId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PULMONARY_NODULE\",\"source\":\"AI_UNVALIDATED\",\"description\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
        String body = mvc.perform(post("/api/v1/imaging-studies/{id}/findings", studyId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PLEURAL_EFFUSION\",\"laterality\":\"LEFT\",\"source\":\"RADIOLOGIST_REPORT\","
                                + "\"status\":\"FINAL\",\"description\":\"Small left pleural effusion\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordedByName").value(clinician.getFullName()))
                .andReturn().getResponse().getContentAsString();
        long findingId = json.readTree(body).get("id").asLong();

        JsonNode a = json.readTree(mvc.perform(post("/api/v1/patients/{id}/cdss/assessments", pt.patient().getId())
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(a.get("alerts").toString()).contains("Imaging: Pleural effusion (left)").contains("radiologist report");
        assertThat(a.get("inputSnapshot").get("imagingFindings").get(0).get("findingId").asLong()).isEqualTo(findingId);

        // Entered-in-error findings drop out of the next assessment.
        mvc.perform(post("/api/v1/imaging-findings/{id}/entered-in-error", findingId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong study\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENTERED_IN_ERROR"));
        JsonNode b = json.readTree(mvc.perform(post("/api/v1/patients/{id}/cdss/assessments", pt.patient().getId())
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(b.get("alerts").toString()).doesNotContain("Pleural effusion");
    }

    @Test
    void patientsSeeOnlyFinalFindings() throws Exception {
        long studyId = uploadStudy(pt.patient(), uid(), pt.patient().getMrn());
        for (String status : new String[] {"FINAL", "PRELIMINARY"}) {
            mvc.perform(post("/api/v1/imaging-studies/{id}/findings", studyId).header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"PULMONARY_NODULE\",\"source\":\"CLINICIAN\",\"status\":\"" + status
                                    + "\",\"description\":\"" + status + " finding\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/v1/imaging-studies/{id}", studyId).header("Authorization", token))
                .andExpect(jsonPath("$.findings.length()").value(2));
        mvc.perform(get("/api/v1/imaging-studies/{id}", studyId).header("Authorization", fx.token(pt.account())))
                .andExpect(jsonPath("$.findings.length()").value(1))
                .andExpect(jsonPath("$.findings[0].status").value("FINAL"));
    }

    @Test
    void ordersAreCompletedWhenAStudyIsAttached() throws Exception {
        String order = mvc.perform(post("/api/v1/patients/{id}/imaging-orders", pt.patient().getId())
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modality\":\"CT\",\"procedureDescription\":\"CT chest\",\"priority\":\"URGENT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessionNumber").value(org.hamcrest.Matchers.startsWith("RDSS")))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        long orderId = json.readTree(order).get("id").asLong();
        String studyUid = uid();
        var req = multipart("/api/v1/patients/{id}/imaging-studies/upload", pt.patient().getId())
                .file(new MockMultipartFile("files", "a.dcm", "application/dicom",
                        StubImagingArchive.dicom(studyUid, uid(), uid(), pt.patient().getMrn(), "CT")))
                .param("orderId", String.valueOf(orderId));
        mvc.perform(req.header("Authorization", token)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.studies[0].orderId").value(orderId));
        mvc.perform(get("/api/v1/patients/{id}/imaging-orders", pt.patient().getId()).header("Authorization", token))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }
}
