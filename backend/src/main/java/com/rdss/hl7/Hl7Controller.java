package com.rdss.hl7;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.PageResponse;
import com.rdss.common.Pages;
import com.rdss.common.error.ConflictException;
import com.rdss.common.error.NotFoundException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hl7")
@Tag(name = "HL7 integration")
public class Hl7Controller {

    public static final String HL7_MEDIA_TYPE = "application/hl7-v2";

    public record MessageSummary(Long id, Hl7Message.Direction direction, Hl7Message.Transport transport,
                                 String messageType, String controlId, String sendingApplication,
                                 String sendingFacility, Hl7Message.Status status, String ackCode, String errorMessage,
                                 int attempts, boolean retryable, Instant nextAttemptAt, int duplicateCount,
                                 Long patientId, String resourceType, String resourceId, Instant createdAt,
                                 Instant processedAt) {
        static MessageSummary from(Hl7Message m) {
            return new MessageSummary(m.getId(), m.getDirection(), m.getTransport(), m.getMessageType(),
                    m.getControlId(), m.getSendingApplication(), m.getSendingFacility(), m.getStatus(), m.getAckCode(),
                    m.getErrorMessage(), m.getAttempts(), m.isRetryable(), m.getNextAttemptAt(), m.getDuplicateCount(),
                    m.getPatientId(), m.getResourceType(), m.getResourceId(), m.getCreatedAt(), m.getProcessedAt());
        }
    }

    public record MessageDetail(MessageSummary summary, String rawMessage, String ackMessage) {
    }

    public record IntegrationStatus(boolean httpInboundEnabled, boolean mllpEnabled, int mllpPort,
                                    boolean outboundEnabled, Set<String> supportedInboundTypes, String hl7Version) {
    }

    private final Hl7InboundService inbound;
    private final Hl7OutboundService outbound;
    private final Hl7MessageRepository messages;
    private final Hl7Properties props;
    private final AuditService audit;

    public Hl7Controller(Hl7InboundService inbound, Hl7OutboundService outbound, Hl7MessageRepository messages,
                         Hl7Properties props, AuditService audit) {
        this.inbound = inbound;
        this.outbound = outbound;
        this.messages = messages;
        this.props = props;
        this.audit = audit;
    }

    /**
     * HTTP transport for inbound HL7 v2 (ER7 text). Authenticated with the shared integration key
     * in {@code X-Integration-Key}; always answers with an HL7 ACK when the key is valid.
     */
    @PostMapping(path = "/messages", consumes = {MediaType.TEXT_PLAIN_VALUE, HL7_MEDIA_TYPE},
            produces = HL7_MEDIA_TYPE)
    @SecurityRequirements
    @Operation(summary = "Receive an HL7 v2 message (integration key required); returns the ACK")
    public ResponseEntity<String> receive(@RequestHeader(name = "X-Integration-Key", required = false) String key,
                                          @RequestBody String body) {
        if (!props.httpInboundEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_PLAIN)
                    .body("HL7 HTTP inbound is not configured");
        }
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                props.inboundApiKey().getBytes(StandardCharsets.UTF_8))) {
            audit.recordAs(null, "HL7_IN", "Hl7Message", null, null, Outcome.DENIED, "invalid integration key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.TEXT_PLAIN)
                    .body("Invalid integration key");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(HL7_MEDIA_TYPE))
                .body(inbound.receive(body, Hl7Message.Transport.HTTP));
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public IntegrationStatus status() {
        return new IntegrationStatus(props.httpInboundEnabled(), props.mllp() != null && props.mllp().enabled(),
                props.mllp() == null ? 0 : props.mllp().port(), outbound.enabled(),
                new TreeSet<>(inbound.supportedTypes()), "2.5 (2.3-2.5.1 accepted)");
    }

    @GetMapping("/messages")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<MessageSummary> list(@RequestParam(required = false) Hl7Message.Direction direction,
                                             @RequestParam(required = false) Hl7Message.Status status,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        String prefix = type == null || type.isBlank() ? null : type.trim().toUpperCase() + "%";
        return PageResponse.of(messages.search(direction, status, prefix,
                Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))), MessageSummary::from);
    }

    /** Full message text contains PHI: administrators only, and each view is audited. */
    @GetMapping("/messages/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public MessageDetail detail(@PathVariable Long id) {
        Hl7Message m = messages.findById(id).orElseThrow(() -> NotFoundException.of("HL7 message", id));
        audit.record("READ", "Hl7Message", id, m.getPatientId(), Outcome.SUCCESS, null);
        return new MessageDetail(MessageSummary.from(m), m.getRawMessage(), m.getAckMessage());
    }

    @PostMapping("/messages/{id}/reprocess")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Re-run a failed or rejected inbound message (e.g. after creating the missing patient)")
    public MessageDetail reprocess(@PathVariable Long id) {
        Hl7Message m = messages.findById(id).orElseThrow(() -> NotFoundException.of("HL7 message", id));
        if (m.getDirection() != Hl7Message.Direction.INBOUND
                || (m.getStatus() != Hl7Message.Status.FAILED && m.getStatus() != Hl7Message.Status.REJECTED)) {
            throw new ConflictException("Only failed or rejected inbound messages can be reprocessed");
        }
        audit.record("REPROCESS", "Hl7Message", id, m.getPatientId(), Outcome.SUCCESS, null);
        inbound.process(id);
        return detail(id);
    }
}
