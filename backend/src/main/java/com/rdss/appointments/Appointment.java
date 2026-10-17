package com.rdss.appointments;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.rdss.common.error.ConflictException;

@Entity
@Table(name = "appointment")
public class Appointment {

    public enum Status {
        REQUESTED, SCHEDULED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW;

        /** Statuses that occupy the time slot (mirrors the DB exclusion constraints). */
        public static final Set<Status> BLOCKING = EnumSet.of(REQUESTED, SCHEDULED, CHECKED_IN, COMPLETED);
    }

    public enum Type { CONSULTATION, FOLLOW_UP, SPIROMETRY, IMAGING_REVIEW, TELEHEALTH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "clinician_id", nullable = false, updatable = false)
    private Long clinicianId;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "appointment_type", nullable = false)
    private Type type;

    private String reason;
    private String notes;

    @Column(name = "encounter_id")
    private Long encounterId;

    @Column(name = "rescheduled_from_id", updatable = false)
    private Long rescheduledFromId;

    /** Placer appointment id from an external scheduling system (HL7 SIU SCH-1). */
    @Column(name = "external_id")
    private String externalId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Appointment() {
    }

    public Appointment(Long patientId, Long clinicianId, Instant startsAt, Instant endsAt, Status status, Type type,
                       String reason, Long createdBy, Long rescheduledFromId) {
        this.patientId = patientId;
        this.clinicianId = clinicianId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = status;
        this.type = type;
        this.reason = reason;
        this.createdBy = createdBy;
        this.rescheduledFromId = rescheduledFromId;
    }

    public void transition(Status target) {
        boolean ok = switch (target) {
            case SCHEDULED -> status == Status.REQUESTED;
            case CHECKED_IN -> status == Status.SCHEDULED;
            case COMPLETED -> status == Status.SCHEDULED || status == Status.CHECKED_IN;
            case NO_SHOW -> status == Status.SCHEDULED || status == Status.REQUESTED;
            case REQUESTED, CANCELLED -> false;
        };
        if (!ok) {
            throw new ConflictException("Cannot change appointment from " + status + " to " + target);
        }
        status = target;
    }

    public void cancel(Long byUserId, String reason, Instant at) {
        if (status != Status.REQUESTED && status != Status.SCHEDULED) {
            throw new ConflictException("Only requested or scheduled appointments can be cancelled (status is "
                    + status + ")");
        }
        status = Status.CANCELLED;
        cancelledAt = at;
        cancelledBy = byUserId;
        cancellationReason = reason;
    }

    public void linkEncounter(Long encounterId) {
        if (this.encounterId != null) {
            throw new ConflictException("Appointment " + id + " already has an encounter");
        }
        this.encounterId = encounterId;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Long getClinicianId() { return clinicianId; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public Status getStatus() { return status; }
    public Type getType() { return type; }
    public String getReason() { return reason; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Long getEncounterId() { return encounterId; }
    public Long getRescheduledFromId() { return rescheduledFromId; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Long getCancelledBy() { return cancelledBy; }
    public String getCancellationReason() { return cancellationReason; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
