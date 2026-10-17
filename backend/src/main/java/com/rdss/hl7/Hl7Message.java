package com.rdss.hl7;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Log entry for every HL7 message exchanged (audit trail, idempotency and retry state). */
@Entity
@Table(name = "hl7_message")
public class Hl7Message {

    public enum Direction { INBOUND, OUTBOUND }

    public enum Transport { HTTP, MLLP }

    public enum Status { RECEIVED, PROCESSED, FAILED, REJECTED, PENDING, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Transport transport;

    @Column(name = "control_id", updatable = false)
    private String controlId;

    @Column(name = "sending_application", updatable = false)
    private String sendingApplication;

    @Column(name = "sending_facility", updatable = false)
    private String sendingFacility;

    @Column(name = "receiving_application", updatable = false)
    private String receivingApplication;

    @Column(name = "receiving_facility", updatable = false)
    private String receivingFacility;

    @Column(name = "message_type", updatable = false)
    private String messageType;

    @Column(name = "hl7_version", updatable = false)
    private String hl7Version;

    @Column(name = "raw_message", nullable = false, updatable = false)
    private String rawMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "ack_code")
    private String ackCode;

    @Column(name = "ack_message")
    private String ackMessage;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(nullable = false)
    private boolean retryable;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected Hl7Message() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public Hl7Message(Direction direction, Transport transport, String raw, Status status, Header h) {
        this.direction = direction;
        this.transport = transport;
        this.rawMessage = raw;
        this.status = status;
        if (h != null) {
            this.controlId = h.controlId();
            this.sendingApplication = h.sendingApplication();
            this.sendingFacility = h.sendingFacility();
            this.receivingApplication = h.receivingApplication();
            this.receivingFacility = h.receivingFacility();
            this.messageType = h.messageType();
            this.hl7Version = h.version();
        }
        this.createdAt = Instant.now();
    }

    /** MSH fields relevant for routing, idempotency and the log. */
    public record Header(String controlId, String sendingApplication, String sendingFacility,
                         String receivingApplication, String receivingFacility, String messageType, String version) {
    }

    public void succeeded(String ackCode, String ack, Long patientId, String resourceType, String resourceId) {
        this.status = direction == Direction.INBOUND ? Status.PROCESSED : Status.SENT;
        this.ackCode = ackCode;
        this.ackMessage = ack;
        this.errorMessage = null;
        this.retryable = false;
        this.nextAttemptAt = null;
        this.patientId = patientId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.processedAt = Instant.now();
    }

    public void failed(Status status, String ackCode, String ack, String error, boolean retryable, Instant nextAttempt) {
        this.status = status;
        this.ackCode = ackCode;
        this.ackMessage = ack;
        this.errorMessage = error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error;
        this.retryable = retryable;
        this.nextAttemptAt = retryable ? nextAttempt : null;
        this.processedAt = Instant.now();
    }

    public void forPatient(Long patientId) {
        this.patientId = patientId;
    }

    public void attempt() {
        attempts++;
    }

    public void duplicateReceived() {
        duplicateCount++;
    }

    public Long getId() { return id; }
    public Direction getDirection() { return direction; }
    public Transport getTransport() { return transport; }
    public String getControlId() { return controlId; }
    public String getSendingApplication() { return sendingApplication; }
    public String getSendingFacility() { return sendingFacility; }
    public String getReceivingApplication() { return receivingApplication; }
    public String getReceivingFacility() { return receivingFacility; }
    public String getMessageType() { return messageType; }
    public String getHl7Version() { return hl7Version; }
    public String getRawMessage() { return rawMessage; }
    public Status getStatus() { return status; }
    public String getAckCode() { return ackCode; }
    public String getAckMessage() { return ackMessage; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isRetryable() { return retryable; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public int getDuplicateCount() { return duplicateCount; }
    public Long getPatientId() { return patientId; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
}
