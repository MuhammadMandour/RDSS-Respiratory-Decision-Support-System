package com.rdss.hl7;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.ErrorCode;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.error.ConflictException;
import com.rdss.system.SettingsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inbound HL7 v2 pipeline, shared by the HTTP and MLLP transports:
 * <ol>
 *   <li>normalize, read MSH leniently (so even unparseable messages are logged and answered)</li>
 *   <li>duplicate detection on (MSH-4, MSH-3, MSH-10): the original ACK is replayed, nothing is re-applied</li>
 *   <li>log the raw message (own transaction), then parse and validate with HAPI</li>
 *   <li>route MSH-9 to a handler; the handler runs in its own transaction</li>
 *   <li>ACK: AA success; AE business error (no retry) or transient failure (retried with backoff);
 *       AR unparseable or unsupported message</li>
 * </ol>
 * Never logs message content (it contains PHI); the content is stored only in hl7_message.
 */
@Service
public class Hl7InboundService {

    private static final Logger log = LoggerFactory.getLogger(Hl7InboundService.class);

    private final HapiContext hapi;
    private final Hl7MessageRepository messages;
    private final Map<String, Hl7MessageHandler> handlers = new HashMap<>();
    private final SystemAccount system;
    private final SettingsService settings;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public Hl7InboundService(HapiContext hapi, Hl7MessageRepository messages, List<Hl7MessageHandler> handlerList,
                             SystemAccount system, SettingsService settings, AuditService audit,
                             PlatformTransactionManager txManager) {
        this.hapi = hapi;
        this.messages = messages;
        this.system = system;
        this.settings = settings;
        this.audit = audit;
        for (Hl7MessageHandler h : handlerList) {
            for (String type : h.messageTypes()) {
                if (handlers.put(type, h) != null) {
                    throw new IllegalStateException("Two HL7 handlers registered for " + type);
                }
            }
        }
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public java.util.Set<String> supportedTypes() {
        return handlers.keySet();
    }

    /** Processes one inbound message and returns the ER7-encoded acknowledgement. */
    public String receive(String raw, Hl7Message.Transport transport) {
        String normalized = Hl7Support.normalize(raw);
        Hl7Message.Header header = Hl7Support.readHeader(normalized);
        if (header == null || header.controlId() == null) {
            Long id = tx.execute(s -> messages.save(rejected(normalized, transport, header,
                    "Missing or invalid MSH segment / MSH-10"))).getId();
            log.warn("Rejected inbound HL7 message #{}: no MSH or MSH-10", id);
            return Hl7Support.rawReject(header, "Missing or invalid MSH segment or control id");
        }

        var existing = messages.findInbound(header.sendingFacility(), header.sendingApplication(), header.controlId());
        if (existing.isPresent()) {
            return replayDuplicate(existing.get().getId());
        }
        Hl7Message entry;
        try {
            entry = tx.execute(s -> messages.saveAndFlush(new Hl7Message(Hl7Message.Direction.INBOUND, transport,
                    normalized, Hl7Message.Status.RECEIVED, header)));
        } catch (DataIntegrityViolationException race) {
            // Same control id arrived concurrently; answer like a duplicate.
            return messages.findInbound(header.sendingFacility(), header.sendingApplication(), header.controlId())
                    .map(m -> replayDuplicate(m.getId()))
                    .orElse(Hl7Support.rawReject(header, "Concurrent duplicate"));
        }
        return process(entry.getId());
    }

    /** (Re)processes a logged inbound message. Used for first delivery, scheduled retries and admin reprocessing. */
    public String process(Long messageId) {
        Hl7Message entry = messages.findById(messageId).orElseThrow();
        String raw = entry.getRawMessage();
        Message msg;
        try {
            msg = hapi.getPipeParser().parse(raw);
        } catch (HL7Exception ex) {
            String ack = Hl7Support.rawReject(Hl7Support.readHeader(raw), "Parse/validation error: " + ex.getMessage());
            finish(messageId, m -> m.failed(Hl7Message.Status.REJECTED, "AR", ack, "Parse/validation error: "
                    + ex.getMessage(), false, null));
            return ack;
        }

        String type = entry.getMessageType();
        Hl7MessageHandler handler = type == null ? null : handlers.get(type);
        if (handler == null) {
            String ack = ack(msg, AcknowledgmentCode.AR, "Unsupported message type " + type, ErrorCode.UNSUPPORTED_MESSAGE_TYPE);
            finish(messageId, m -> m.failed(Hl7Message.Status.REJECTED, "AR", ack, "Unsupported message type " + type,
                    false, null));
            return ack;
        }

        try {
            Hl7MessageHandler.Result result = tx.execute(s -> {
                try {
                    return handler.handle(msg, new Terser(msg));
                } catch (HL7Exception ex) {
                    throw new Hl7ProcessingException("Invalid message content: " + ex.getMessage());
                }
            });
            String ack = ack(msg, AcknowledgmentCode.AA, null, null);
            finish(messageId, m -> m.succeeded("AA", ack, result.patientId(), result.resourceType(), result.resourceId()));
            audit.recordAs(system.principal(), "HL7_IN", result.resourceType() == null ? "Hl7Message" : result.resourceType(),
                    result.resourceId(), result.patientId(), Outcome.SUCCESS, type + " #" + messageId + ": " + result.note());
            return ack;
        } catch (Hl7ProcessingException | ConflictException | DataIntegrityViolationException
                 | IllegalArgumentException business) {
            String text = business instanceof DataIntegrityViolationException
                    ? "Data rejected by RDSS validation (value out of range or conflicting record)" : business.getMessage();
            String ack = ack(msg, AcknowledgmentCode.AE, text, ErrorCode.APPLICATION_RECORD_LOCKED);
            finish(messageId, m -> m.failed(Hl7Message.Status.FAILED, "AE", ack, text, false, null));
            audit.recordAs(system.principal(), "HL7_IN", "Hl7Message", messageId, null, Outcome.FAILURE, type + ": " + text);
            return ack;
        } catch (RuntimeException transientError) {
            log.error("HL7 message #{} ({}) failed; will retry", messageId, type, transientError);
            int max = settings.getInt("hl7.retry.max_attempts", 5);
            Hl7Message current = messages.findById(messageId).orElseThrow();
            boolean retry = current.getAttempts() + 1 < max;
            Instant next = Instant.now().plus(Duration.ofMinutes(1L << Math.min(current.getAttempts(), 6)));
            String text = "Temporary processing error" + (retry ? "; will be retried" : "; retries exhausted");
            String ack = ack(msg, AcknowledgmentCode.AE, text, ErrorCode.APPLICATION_INTERNAL_ERROR);
            finish(messageId, m -> m.failed(Hl7Message.Status.FAILED, "AE", ack, text, retry, next));
            return ack;
        }
    }

    private String replayDuplicate(Long id) {
        Hl7Message m = tx.execute(s -> {
            Hl7Message x = messages.findById(id).orElseThrow();
            x.duplicateReceived();
            return x;
        });
        audit.recordAs(system.principal(), "HL7_DUPLICATE", "Hl7Message", id, m.getPatientId(), Outcome.SUCCESS,
                "control id " + m.getControlId());
        return m.getAckMessage() != null ? m.getAckMessage()
                : Hl7Support.rawReject(new Hl7Message.Header(m.getControlId(), m.getSendingApplication(),
                        m.getSendingFacility(), null, null, m.getMessageType(), m.getHl7Version()),
                        "Message is still being processed");
    }

    private void finish(Long id, java.util.function.Consumer<Hl7Message> update) {
        tx.executeWithoutResult(s -> {
            Hl7Message m = messages.findById(id).orElseThrow();
            m.attempt();
            update.accept(m);
        });
    }

    private Hl7Message rejected(String raw, Hl7Message.Transport transport, Hl7Message.Header h, String error) {
        Hl7Message m = new Hl7Message(Hl7Message.Direction.INBOUND, transport, raw.isEmpty() ? "(empty)" : raw,
                Hl7Message.Status.REJECTED, h == null ? null
                : new Hl7Message.Header(null, h.sendingApplication(), h.sendingFacility(), h.receivingApplication(),
                        h.receivingFacility(), h.messageType(), h.version()));
        m.failed(Hl7Message.Status.REJECTED, "AR", null, error, false, null);
        return m;
    }

    private String ack(Message msg, AcknowledgmentCode code, String text, ErrorCode errorCode) {
        try {
            Message ack = code == AcknowledgmentCode.AA ? msg.generateACK()
                    : msg.generateACK(code, new HL7Exception(text, errorCode));
            return hapi.getPipeParser().encode(ack);
        } catch (Exception ex) {
            log.warn("Could not build ACK with HAPI: {}", ex.getMessage());
            return Hl7Support.rawReject(Hl7Support.readHeader(safeEncode(msg)), text);
        }
    }

    private String safeEncode(Message msg) {
        try {
            return msg.encode();
        } catch (HL7Exception e) {
            return "";
        }
    }
}
