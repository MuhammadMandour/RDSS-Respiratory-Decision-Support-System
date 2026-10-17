package com.rdss.imaging.model;

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

@Entity
@Table(name = "imaging_order")
public class ImagingOrder {

    public enum Status { REQUESTED, SCHEDULED, COMPLETED, CANCELLED }

    public enum Priority { ROUTINE, URGENT, STAT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "encounter_id")
    private Long encounterId;

    @Column(name = "accession_number", nullable = false, updatable = false)
    private String accessionNumber;

    @Column(nullable = false)
    private String modality;

    @Column(name = "body_part", nullable = false)
    private String bodyPart;

    @Column(name = "procedure_description", nullable = false)
    private String procedureDescription;

    @Column(name = "clinical_indication")
    private String clinicalIndication;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.ROUTINE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.REQUESTED;

    @Column(name = "external_order_id")
    private String externalOrderId;

    @Column(name = "ordered_by")
    private Long orderedBy;

    @Column(name = "ordered_at", nullable = false, updatable = false)
    private Instant orderedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ImagingOrder() {
    }

    public ImagingOrder(Long patientId, Long encounterId, String accessionNumber, String modality, String bodyPart,
                        String procedureDescription, String clinicalIndication, Priority priority, Long orderedBy) {
        this.patientId = patientId;
        this.encounterId = encounterId;
        this.accessionNumber = accessionNumber;
        this.modality = modality;
        this.bodyPart = bodyPart;
        this.procedureDescription = procedureDescription;
        this.clinicalIndication = clinicalIndication;
        this.priority = priority;
        this.orderedBy = orderedBy;
    }

    public void cancel() {
        if (status == Status.COMPLETED) {
            throw new ConflictException("A completed imaging order cannot be cancelled");
        }
        status = Status.CANCELLED;
    }

    public void complete() {
        if (status == Status.CANCELLED) {
            throw new ConflictException("Cannot attach a study to a cancelled order");
        }
        status = Status.COMPLETED;
    }

    public void setStatus(Status status) { this.status = status; }
    public void setExternalOrderId(String v) { this.externalOrderId = v; }

    @PrePersist
    void onCreate() {
        orderedAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Long getEncounterId() { return encounterId; }
    public String getAccessionNumber() { return accessionNumber; }
    public String getModality() { return modality; }
    public String getBodyPart() { return bodyPart; }
    public String getProcedureDescription() { return procedureDescription; }
    public String getClinicalIndication() { return clinicalIndication; }
    public Priority getPriority() { return priority; }
    public Status getStatus() { return status; }
    public String getExternalOrderId() { return externalOrderId; }
    public Long getOrderedBy() { return orderedBy; }
    public Instant getOrderedAt() { return orderedAt; }
}
