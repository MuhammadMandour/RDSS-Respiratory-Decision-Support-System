package com.rdss.hl7;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.Connection;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.message.ADT_A01;
import ca.uhn.hl7v2.model.v25.segment.PID;
import ca.uhn.hl7v2.util.Terser;

import com.rdss.patients.Patient;
import com.rdss.patients.PatientChangedEvent;
import com.rdss.patients.PatientRepository;
import com.rdss.system.SettingsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbound ADT^A08 (update patient information) over MLLP to the endpoint configured in system
 * settings. Disabled unless {@code hl7.outbound.enabled=true} and a host is set. Every message is
 * logged first (PENDING) and sent with retry/backoff until the receiver returns AA/CA.
 */
@Service
public class Hl7OutboundService {

    private static final Logger log = LoggerFactory.getLogger(Hl7OutboundService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final HapiContext hapi;
    private final Hl7MessageRepository messages;
    private final PatientRepository patients;
    private final PatientIdentifierRepository identifiers;
    private final SettingsService settings;
    private final TransactionTemplate tx;

    public Hl7OutboundService(HapiContext hapi, Hl7MessageRepository messages, PatientRepository patients,
                              PatientIdentifierRepository identifiers, SettingsService settings,
                              PlatformTransactionManager txManager) {
        this.hapi = hapi;
        this.messages = messages;
        this.patients = patients;
        this.identifiers = identifiers;
        this.settings = settings;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean enabled() {
        return "true".equals(settings.get("hl7.outbound.enabled", "false"))
                && !settings.get("hl7.outbound.host", "").isBlank();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPatientChanged(PatientChangedEvent event) {
        if (!enabled()) {
            return;
        }
        try {
            Long id = enqueueA08(event.patientId());
            send(id);
        } catch (RuntimeException ex) {
            // Never fail the user's request because an interface is down; the retry job picks it up.
            log.warn("Outbound HL7 for patient change could not be sent now: {}", ex.getMessage());
        }
    }

    /** Builds and logs an ADT^A08 for the patient; returns the log id. */
    public Long enqueueA08(Long patientId) {
        return tx.execute(s -> {
            Patient p = patients.findById(patientId).orElseThrow();
            String raw = buildA08(p);
            Hl7Message m = new Hl7Message(Hl7Message.Direction.OUTBOUND, Hl7Message.Transport.MLLP, raw,
                    Hl7Message.Status.PENDING, Hl7Support.readHeader(raw));
            m.failed(Hl7Message.Status.PENDING, null, null, null, true, Instant.now());
            m.forPatient(patientId);
            return messages.save(m).getId();
        });
    }

    /** Attempts delivery; on failure schedules a retry with exponential backoff. */
    public void send(Long messageId) {
        Hl7Message m = messages.findById(messageId).orElseThrow();
        String host = settings.get("hl7.outbound.host", "");
        int port = settings.getInt("hl7.outbound.port", 2575);
        int max = settings.getInt("hl7.retry.max_attempts", 5);
        Connection conn = null;
        try {
            Message msg = hapi.getPipeParser().parse(m.getRawMessage());
            conn = hapi.newClient(host, port, false);
            conn.getInitiator().setTimeout(15, TimeUnit.SECONDS);
            Message response = conn.getInitiator().sendAndReceive(msg);
            String ack = hapi.getPipeParser().encode(response);
            String code = new Terser(response).get("/MSA-1");
            update(messageId, x -> {
                x.attempt();
                if ("AA".equals(code) || "CA".equals(code)) {
                    x.succeeded(code, ack, x.getPatientId(), "Patient", String.valueOf(x.getPatientId()));
                } else {
                    boolean retry = "AE".equals(code) || "CE".equals(code);
                    x.failed(Hl7Message.Status.FAILED, code, ack, "Receiver answered " + code,
                            retry && x.getAttempts() < max, backoff(x.getAttempts()));
                }
            });
        } catch (Exception ex) {
            log.warn("Outbound HL7 message #{} to {}:{} failed: {}", messageId, host, port, ex.getMessage());
            update(messageId, x -> {
                x.attempt();
                x.failed(Hl7Message.Status.FAILED, null, null, "Delivery failed: " + ex.getClass().getSimpleName(),
                        x.getAttempts() < max, backoff(x.getAttempts()));
            });
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // connection already gone
                }
            }
        }
    }

    private void update(Long id, java.util.function.Consumer<Hl7Message> change) {
        tx.executeWithoutResult(s -> change.accept(messages.findById(id).orElseThrow()));
    }

    private static Instant backoff(int attempts) {
        return Instant.now().plus(Duration.ofMinutes(1L << Math.min(attempts, 6)));
    }

    String buildA08(Patient p) {
        try {
            ADT_A01 adt = new ADT_A01();
            adt.initQuickstart("ADT", "A08", "P");
            adt.getMSH().getVersionID().getVersionID().setValue("2.5");
            adt.getMSH().getMessageType().getMessageStructure().setValue("ADT_A01");
            adt.getMSH().getSendingApplication().getNamespaceID().setValue("RDSS");
            adt.getMSH().getSendingFacility().getNamespaceID().setValue(settings.get("hl7.rdss_assigning_authority", "RDSS"));
            adt.getMSH().getMessageControlID().setValue("RDSS" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            adt.getEVN().getRecordedDateTime().getTime().setValue(TS.format(Instant.now()));
            adt.getEVN().getEventTypeCode().setValue("A08");
            PID pid = adt.getPID();
            pid.getSetIDPID().setValue("1");
            pid.getPatientIdentifierList(0).getIDNumber().setValue(p.getMrn());
            pid.getPatientIdentifierList(0).getAssigningAuthority().getNamespaceID()
                    .setValue(settings.get("hl7.rdss_assigning_authority", "RDSS"));
            pid.getPatientIdentifierList(0).getIdentifierTypeCode().setValue("MR");
            int rep = 1;
            for (PatientIdentifier id : identifiers.findByPatientId(p.getId())) {
                pid.getPatientIdentifierList(rep).getIDNumber().setValue(id.getValue());
                pid.getPatientIdentifierList(rep).getAssigningAuthority().getNamespaceID().setValue(id.getSystem());
                rep++;
            }
            pid.getPatientName(0).getFamilyName().getSurname().setValue(p.getLastName());
            pid.getPatientName(0).getGivenName().setValue(p.getFirstName());
            pid.getDateTimeOfBirth().getTime().setValue(p.getDateOfBirth().format(DateTimeFormatter.BASIC_ISO_DATE));
            pid.getAdministrativeSex().setValue(switch (p.getSex()) {
                case FEMALE -> "F";
                case MALE -> "M";
                case OTHER -> "O";
                case UNKNOWN -> "U";
            });
            if (p.getAddressLine() != null) {
                pid.getPatientAddress(0).getStreetAddress().getStreetOrMailingAddress().setValue(p.getAddressLine());
            }
            if (p.getCity() != null) {
                pid.getPatientAddress(0).getCity().setValue(p.getCity());
            }
            if (p.getPostalCode() != null) {
                pid.getPatientAddress(0).getZipOrPostalCode().setValue(p.getPostalCode());
            }
            if (p.getPhone() != null) {
                pid.getPhoneNumberHome(0).getTelephoneNumber().setValue(p.getPhone());
            }
            adt.getPV1().getPatientClass().setValue("N");
            return hapi.getPipeParser().encode(adt);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build ADT^A08", ex);
        }
    }
}
