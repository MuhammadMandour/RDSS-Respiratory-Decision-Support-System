package com.rdss.encounters;

import java.time.Instant;

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

/** A prescription's clinical content is immutable; only its status changes. Re-prescribe to change dosing. */
@Entity
@Table(name = "prescription")
public class Prescription {

    public enum Status { ACTIVE, COMPLETED, DISCONTINUED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "encounter_id", updatable = false)
    private Long encounterId;

    @Column(name = "medication_name", nullable = false, updatable = false)
    private String medicationName;

    @Column(nullable = false, updatable = false)
    private String dose;

    @Column(nullable = false, updatable = false)
    private String route;

    @Column(nullable = false, updatable = false)
    private String frequency;

    @Column(updatable = false)
    private String duration;

    @Column(updatable = false)
    private String quantity;

    @Column(nullable = false, updatable = false)
    private int refills;

    @Column(updatable = false)
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "discontinued_reason")
    private String discontinuedReason;

    @Column(name = "prescribed_by", nullable = false, updatable = false)
    private Long prescribedBy;

    @Column(name = "prescribed_at", nullable = false, updatable = false)
    private Instant prescribedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Prescription() {
    }

    public Prescription(Long patientId, Long encounterId, String medicationName, String dose, String route,
                        String frequency, String duration, String quantity, int refills, String instructions,
                        Long prescribedBy) {
        this.patientId = patientId;
        this.encounterId = encounterId;
        this.medicationName = medicationName;
        this.dose = dose;
        this.route = route;
        this.frequency = frequency;
        this.duration = duration;
        this.quantity = quantity;
        this.refills = refills;
        this.instructions = instructions;
        this.prescribedBy = prescribedBy;
    }

    public void discontinue(String reason) {
        requireActive();
        status = Status.DISCONTINUED;
        discontinuedReason = reason;
    }

    public void complete() {
        requireActive();
        status = Status.COMPLETED;
    }

    private void requireActive() {
        if (status != Status.ACTIVE) {
            throw new ConflictException("Prescription " + id + " is already " + status);
        }
    }

    @PrePersist
    void onCreate() {
        prescribedAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Long getEncounterId() { return encounterId; }
    public String getMedicationName() { return medicationName; }
    public String getDose() { return dose; }
    public String getRoute() { return route; }
    public String getFrequency() { return frequency; }
    public String getDuration() { return duration; }
    public String getQuantity() { return quantity; }
    public int getRefills() { return refills; }
    public String getInstructions() { return instructions; }
    public Status getStatus() { return status; }
    public String getDiscontinuedReason() { return discontinuedReason; }
    public Long getPrescribedBy() { return prescribedBy; }
    public Instant getPrescribedAt() { return prescribedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
