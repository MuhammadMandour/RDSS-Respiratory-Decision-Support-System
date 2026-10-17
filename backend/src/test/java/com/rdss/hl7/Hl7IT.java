package com.rdss.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.HL7Service;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.NoValidation;

import com.rdss.appointments.Appointment;
import com.rdss.appointments.AppointmentRepository;
import com.rdss.imaging.model.ImagingOrder;
import com.rdss.imaging.model.ImagingOrderRepository;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientRepository;
import com.rdss.respiratory.ObservationRepository;
import com.rdss.support.IntegrationTest;
import com.rdss.support.TestFixtures;
import com.rdss.users.AppUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class Hl7IT {

    static final String KEY = "test-integration-key-0123456789";
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired PatientRepository patients;
    @Autowired PatientIdentifierRepository identifiers;
    @Autowired ObservationRepository observations;
    @Autowired ImagingOrderRepository orders;
    @Autowired AppointmentRepository appointments;
    @Autowired Hl7MessageRepository messages;
    @Autowired Hl7InboundService inbound;

    String externalId;
    String adminToken;

    @BeforeEach
    void setUp() {
        externalId = "EXT" + UUID.randomUUID().toString().substring(0, 8);
        adminToken = fx.token(fx.admin());
    }

    static String control() {
        return "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
    }

    static String msh(String type, String control) {
        return "MSH|^~\\&|EMR|CITYHOSP|RDSS|RDSS|" + TS.format(Instant.now()) + "||" + type + "|" + control + "|P|2.5";
    }

    static String msg(String... segments) {
        return String.join("\r", segments) + "\r";
    }

    String pid(String family, String given) {
        return "PID|1||" + externalId + "^^^CITYHOSP^MR||" + family + "^" + given + "||19800215|F|||12 Lung St^^Springfield^^12345^Demo||+1 555 0101";
    }

    String adt(String trigger, String control, String family) {
        return msg(msh("ADT^" + trigger + "^ADT_A01", control), "EVN|" + trigger + "|" + TS.format(Instant.now()),
                pid(family, "Avery"), "PV1|1|O");
    }

    ResultActions send(String body) throws Exception {
        return mvc.perform(post("/api/v1/hl7/messages").header("X-Integration-Key", KEY)
                .contentType(MediaType.parseMediaType("application/hl7-v2")).content(body));
    }

    static String ackCode(String ack) {
        for (String seg : ack.split("\r")) {
            if (seg.startsWith("MSA|")) {
                return seg.split("\\|")[1];
            }
        }
        return null;
    }

    String sendAndAck(String body) throws Exception {
        return send(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    Patient registered() throws Exception {
        assertThat(ackCode(sendAndAck(adt("A04", control(), "Testperson")))).isEqualTo("AA");
        return patients.findById(identifiers.findBySystemAndValue("CITYHOSP", externalId).orElseThrow().getPatientId())
                .orElseThrow();
    }

    @Test
    void adtRegistersPatientMapsIdentifierAndUpdatesOnA08() throws Exception {
        Patient p = registered();
        assertThat(p.getLastName()).isEqualTo("Testperson");
        assertThat(p.getSex()).isEqualTo(Patient.Sex.FEMALE);
        assertThat(p.getCity()).isEqualTo("Springfield");
        assertThat(p.getMrn()).startsWith("MRN");

        assertThat(ackCode(sendAndAck(adt("A08", control(), "Renamed")))).isEqualTo("AA");
        assertThat(patients.findById(p.getId()).orElseThrow().getLastName()).isEqualTo("Renamed");
        // A08 updated, did not create a second patient.
        assertThat(identifiers.findByPatientId(p.getId())).hasSize(1);
    }

    @Test
    void duplicateControlIdReplaysOriginalAckWithoutReapplying() throws Exception {
        String control = control();
        String first = sendAndAck(adt("A04", control, "Dupe"));
        long before = patients.count();
        String second = sendAndAck(adt("A04", control, "Different"));
        assertThat(second).isEqualTo(first);
        assertThat(patients.count()).isEqualTo(before);
        Hl7Message logged = messages.findInbound("CITYHOSP", "EMR", control).orElseThrow();
        assertThat(logged.getDuplicateCount()).isEqualTo(1);
        assertThat(logged.getStatus()).isEqualTo(Hl7Message.Status.PROCESSED);
    }

    @Test
    void integrationKeyIsRequired() throws Exception {
        mvc.perform(post("/api/v1/hl7/messages").contentType(MediaType.TEXT_PLAIN).content(adt("A04", control(), "X")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/hl7/messages").header("X-Integration-Key", "wrong").contentType(MediaType.TEXT_PLAIN)
                        .content(adt("A04", control(), "X")))
                .andExpect(status().isUnauthorized());
        // A user JWT is not an integration credential.
        mvc.perform(post("/api/v1/hl7/messages").header("Authorization", adminToken).contentType(MediaType.TEXT_PLAIN)
                        .content(adt("A04", control(), "X")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedAndUnsupportedMessagesAreRejectedWithAr() throws Exception {
        assertThat(ackCode(sendAndAck("this is not hl7"))).isEqualTo("AR");
        String unsupported = msg(msh("ADT^A40^ADT_A39", control()), "EVN|A40", pid("X", "Y"));
        assertThat(ackCode(sendAndAck(unsupported))).isEqualTo("AR");
        String badType = msg(msh("ORU^R01^ORU_R01", control()), pid("X", "Y"), "OBR|1", "OBX|1|NM|9279-1^RR^LN||not-a-number|/min|||||F");
        assertThat(ackCode(sendAndAck(badType))).isIn("AR", "AE");
    }

    @Test
    void oruImportsLoincVitalsWithUnitConversionAndIgnoresUnknownCodes() throws Exception {
        Patient p = registered();
        String t = TS.format(Instant.now().minus(Duration.ofMinutes(10)));
        String oru = msg(msh("ORU^R01^ORU_R01", control()), pid("Testperson", "Avery"),
                "OBR|1|ORD1|FIL1|85353-1^Vital signs panel^LN|||" + t,
                "OBX|1|NM|9279-1^Respiratory rate^LN||22|/min|||||F|||" + t,
                "OBX|2|NM|59408-5^SpO2 by pulse oximetry^LN||93|%|||||F",
                "OBX|3|NM|8310-5^Body temperature^LN||100.4|[degF]|||||F",
                "OBX|4|NM|99999-9^Not supported^LN||1|x|||||F");
        String ack = sendAndAck(oru);
        assertThat(ackCode(ack)).isEqualTo("AA");
        var obs = observations.findByPatientId(p.getId(), PageRequest.of(0, 5)).getContent();
        assertThat(obs).hasSize(1);
        assertThat(obs.get(0).getRespiratoryRate()).isEqualTo(22);
        assertThat(obs.get(0).getSpo2()).isEqualTo(93);
        assertThat(obs.get(0).getTemperatureC()).isEqualByComparingTo("38.0");
        assertThat(obs.get(0).getNotes()).contains("HL7 ORU^R01");
        Hl7Message logged = messages.findAll().stream().filter(m -> "ORU^R01".equals(m.getMessageType())
                && p.getId().equals(m.getPatientId())).findFirst().orElseThrow();
        assertThat(logged.getStatus()).isEqualTo(Hl7Message.Status.PROCESSED);
    }

    @Test
    void oruForUnknownPatientIsAeAndCanBeReprocessedAfterRegistration() throws Exception {
        String control = control();
        String oru = msg(msh("ORU^R01^ORU_R01", control), pid("Later", "Avery"),
                "OBR|1|ORD1|FIL1|85353-1^Vital signs^LN",
                "OBX|1|NM|9279-1^Respiratory rate^LN||18|/min|||||F");
        assertThat(ackCode(sendAndAck(oru))).isEqualTo("AE");
        Hl7Message failed = messages.findInbound("CITYHOSP", "EMR", control).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(Hl7Message.Status.FAILED);
        assertThat(failed.isRetryable()).isFalse();
        assertThat(failed.getErrorMessage()).contains("Unknown patient identifier");

        registered();
        mvc.perform(post("/api/v1/hl7/messages/{id}/reprocess", failed.getId()).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("PROCESSED"))
                .andExpect(jsonPath("$.summary.ackCode").value("AA"));
        // Message log access is admin-only.
        mvc.perform(get("/api/v1/hl7/messages/{id}", failed.getId()).header("Authorization", fx.token(fx.clinician())))
                .andExpect(status().isForbidden());
    }

    @Test
    void outOfRangeValuesAreRejectedNotClippedAndNotRetried() throws Exception {
        registered();
        String control = control();
        String oru = msg(msh("ORU^R01^ORU_R01", control), pid("Testperson", "Avery"), "OBR|1|ORD1|FIL1|85353-1^Vitals^LN",
                "OBX|1|NM|59408-5^SpO2^LN||150|%|||||F");
        assertThat(ackCode(sendAndAck(oru))).isEqualTo("AE");
        Hl7Message m = messages.findInbound("CITYHOSP", "EMR", control).orElseThrow();
        assertThat(m.isRetryable()).isFalse();
    }

    @Test
    void ormCreatesAndCancelsImagingOrderIdempotently() throws Exception {
        Patient p = registered();
        String placer = "PL" + UUID.randomUUID().toString().substring(0, 6);
        String obr = "OBR|1|" + placer + "||71046^Chest X-ray 2 views^CPT|||||||||Cough 3 weeks|||||||||||DX";
        String nw = msg(msh("ORM^O01^ORM_O01", control()), pid("Testperson", "Avery"), "ORC|NW|" + placer, obr);
        assertThat(ackCode(sendAndAck(nw))).isEqualTo("AA");
        ImagingOrder o = orders.findByExternalOrderId(placer).orElseThrow();
        assertThat(o.getPatientId()).isEqualTo(p.getId());
        assertThat(o.getModality()).isEqualTo("DX");
        assertThat(o.getProcedureDescription()).isEqualTo("Chest X-ray 2 views");
        assertThat(o.getClinicalIndication()).isEqualTo("Cough 3 weeks");
        // Re-sent NW with a new control id does not duplicate the order.
        assertThat(ackCode(sendAndAck(msg(msh("ORM^O01^ORM_O01", control()), pid("Testperson", "Avery"),
                "ORC|NW|" + placer, obr)))).isEqualTo("AA");
        assertThat(orders.findByPatientIdOrderByOrderedAtDesc(p.getId())).hasSize(1);

        assertThat(ackCode(sendAndAck(msg(msh("ORM^O01^ORM_O01", control()), pid("Testperson", "Avery"),
                "ORC|CA|" + placer, obr)))).isEqualTo("AA");
        assertThat(orders.findByExternalOrderId(placer).orElseThrow().getStatus()).isEqualTo(ImagingOrder.Status.CANCELLED);
    }

    @Test
    void siuBooksWithConflictCheckAndCancels() throws Exception {
        Patient p = registered();
        AppUser clinician = fx.clinician();
        String start = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)
                .format(Instant.now().plus(Duration.ofDays(5)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)) + "+0000";
        String appt = "AP" + UUID.randomUUID().toString().substring(0, 6);
        String s12 = msg(msh("SIU^S12^SIU_S12", control()), "SCH|" + appt + "||||||^Follow-up review",
                pid("Testperson", "Avery"), "RGS|1", "AIS|1||CONSULT^Consultation|" + start + "|||30|MIN",
                "AIP|1||" + clinician.getUsername() + "^Clinician^Test");
        assertThat(ackCode(sendAndAck(s12))).isEqualTo("AA");
        Appointment a = appointments.findByExternalId(appt).orElseThrow();
        assertThat(a.getPatientId()).isEqualTo(p.getId());
        assertThat(a.getClinicianId()).isEqualTo(clinician.getId());
        assertThat(Duration.between(a.getStartsAt(), a.getEndsAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(a.getReason()).isEqualTo("Follow-up review");

        // A different booking at the same time for the same clinician conflicts.
        String clash = msg(msh("SIU^S12^SIU_S12", control()), "SCH|" + appt + "X||||||^Clash",
                pid("Testperson", "Avery"), "RGS|1", "AIS|1||CONSULT^Consultation|" + start + "|||30|MIN",
                "AIP|1||" + clinician.getUsername());
        assertThat(ackCode(sendAndAck(clash))).isEqualTo("AE");
        // Unknown clinician.
        String unknown = msg(msh("SIU^S12^SIU_S12", control()), "SCH|" + appt + "Y",
                pid("Testperson", "Avery"), "RGS|1", "AIS|1||CONSULT|" + start + "|||30|MIN", "AIP|1||nobody.here");
        assertThat(ackCode(sendAndAck(unknown))).isEqualTo("AE");

        String s15 = msg(msh("SIU^S15^SIU_S12", control()), "SCH|" + appt, pid("Testperson", "Avery"), "RGS|1",
                "AIS|1||CONSULT|" + start, "AIP|1||" + clinician.getUsername());
        assertThat(ackCode(sendAndAck(s15))).isEqualTo("AA");
        assertThat(appointments.findByExternalId(appt).orElseThrow().getStatus()).isEqualTo(Appointment.Status.CANCELLED);
    }

    @Test
    void outboundA08IsDeliveredOverMllpWhenConfigured() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        List<String> received = new CopyOnWriteArrayList<>();
        HapiContext ctx = new DefaultHapiContext();
        ctx.setValidationContext(new NoValidation());
        HL7Service receiver = ctx.newServer(port, false);
        receiver.registerApplication("*", "*", new ReceivingApplication<Message>() {
            public Message processMessage(Message m, Map<String, Object> meta) throws HL7Exception {
                received.add(m.encode());
                try {
                    return m.generateACK();
                } catch (java.io.IOException e) {
                    throw new HL7Exception(e);
                }
            }

            public boolean canProcess(Message m) {
                return true;
            }
        });
        receiver.startAndWait();
        try {
            setting("hl7.outbound.host", "127.0.0.1");
            setting("hl7.outbound.port", String.valueOf(port));
            setting("hl7.outbound.enabled", "true");

            AppUser clinician = fx.clinician();
            mvc.perform(post("/api/v1/patients").header("Authorization", fx.token(clinician))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"firstName\":\"Outbound\",\"lastName\":\"Synthetic\",\"dateOfBirth\":\"1975-03-03\",\"sex\":\"MALE\"}"))
                    .andExpect(status().isCreated());

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertThat(received).hasSize(1);
            Message a08 = ctx.getPipeParser().parse(received.get(0));
            Terser t = new Terser(a08);
            assertThat(t.get("/MSH-9-1") + "^" + t.get("/MSH-9-2")).isEqualTo("ADT^A08");
            assertThat(t.get("/.PID-5-1")).isEqualTo("Synthetic");
            assertThat(t.get("/.PID-3-4-1")).isEqualTo("RDSS");
            Hl7Message logged = messages.findAll().stream()
                    .filter(m -> m.getDirection() == Hl7Message.Direction.OUTBOUND).reduce((x, y) -> y).orElseThrow();
            assertThat(logged.getStatus()).isEqualTo(Hl7Message.Status.SENT);
            assertThat(logged.getAckCode()).isEqualTo("AA");
        } finally {
            setting("hl7.outbound.enabled", "false");
            receiver.stopAndWait();
            ctx.close();
        }
    }

    @Test
    void mllpListenerUsesTheSamePipeline() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        var server = new Hl7MllpServer(inbound, new Hl7Properties(KEY, new Hl7Properties.Mllp(true, port), Duration.ofHours(1)));
        server.start();
        HapiContext ctx = new DefaultHapiContext();
        ctx.setValidationContext(new NoValidation());
        try {
            var conn = ctx.newClient("127.0.0.1", port, false);
            String control = control();
            Message response = conn.getInitiator().sendAndReceive(ctx.getPipeParser().parse(adt("A04", control, "Mllp")));
            assertThat(new Terser(response).get("/MSA-1")).isEqualTo("AA");
            assertThat(messages.findInbound("CITYHOSP", "EMR", control).orElseThrow().getTransport())
                    .isEqualTo(Hl7Message.Transport.MLLP);
            conn.close();
        } finally {
            server.stop();
            ctx.close();
        }
    }

    private void setting(String key, String value) throws Exception {
        mvc.perform(put("/api/v1/settings/{key}", key).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"" + value + "\"}"))
                .andExpect(status().isOk());
    }
}
