package com.rdss.hl7;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An external system's identifier for an RDSS patient (e.g. another EMR's MRN). */
@Entity
@Table(name = "patient_identifier")
public class PatientIdentifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(nullable = false, updatable = false)
    private String system;

    @Column(nullable = false, updatable = false)
    private String value;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PatientIdentifier() {
    }

    public PatientIdentifier(Long patientId, String system, String value) {
        this.patientId = patientId;
        this.system = system;
        this.value = value;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public String getSystem() { return system; }
    public String getValue() { return value; }
}
