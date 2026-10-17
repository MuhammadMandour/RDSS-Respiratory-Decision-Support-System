package com.rdss.diagnoses;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.rdss.common.error.ConflictException;

/** A coded diagnosis. Code, version and description are snapshotted at the time of recording. */
@Entity
@Table(name = "diagnosis")
public class Diagnosis {

    public enum Type { PRIMARY, SECONDARY, COMORBIDITY }

    public enum Status { ACTIVE, RESOLVED, ENTERED_IN_ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "encounter_id", updatable = false)
    private Long encounterId;

    @Column(name = "icd10_code", nullable = false, updatable = false)
    private String icd10Code;

    @Column(name = "code_version", nullable = false, updatable = false)
    private String codeVersion;

    @Column(nullable = false, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "diagnosis_type", nullable = false, updatable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "onset_date", updatable = false)
    private LocalDate onsetDate;

    @Column(name = "resolved_date")
    private LocalDate resolvedDate;

    @Column(updatable = false)
    private String notes;

    @Column(name = "error_reason")
    private String errorReason;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected Diagnosis() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public Diagnosis(Long patientId, Long encounterId, Icd10Code code, Type type, LocalDate onsetDate, String notes,
                     Long recordedBy) {
        this.patientId = patientId;
        this.encounterId = encounterId;
        this.icd10Code = code.getCode();
        this.codeVersion = code.getCodeVersion();
        this.description = code.getDescription();
        this.type = type;
        this.onsetDate = onsetDate;
        this.notes = notes;
        this.recordedBy = recordedBy;
        this.recordedAt = Instant.now();
    }

    public void resolve(LocalDate date) {
        if (status != Status.ACTIVE) {
            throw new ConflictException("Only active diagnoses can be resolved");
        }
        status = Status.RESOLVED;
        resolvedDate = date;
    }

    public void markEnteredInError(String reason) {
        if (status == Status.ENTERED_IN_ERROR) {
            throw new ConflictException("Diagnosis " + id + " is already marked as entered in error");
        }
        status = Status.ENTERED_IN_ERROR;
        errorReason = reason;
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Long getEncounterId() { return encounterId; }
    public String getIcd10Code() { return icd10Code; }
    public String getCodeVersion() { return codeVersion; }
    public String getDescription() { return description; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public LocalDate getOnsetDate() { return onsetDate; }
    public LocalDate getResolvedDate() { return resolvedDate; }
    public String getNotes() { return notes; }
    public String getErrorReason() { return errorReason; }
    public Long getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
