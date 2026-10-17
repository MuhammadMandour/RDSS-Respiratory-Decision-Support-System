package com.rdss.imaging.model;

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

/** RDSS-side record of a study held in Orthanc. Pixel data is never stored in PostgreSQL. */
@Entity
@Table(name = "imaging_study")
public class ImagingStudy {

    public enum Source { UPLOAD, LINKED, DEMO, HL7 }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "encounter_id")
    private Long encounterId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "study_instance_uid", nullable = false, updatable = false)
    private String studyInstanceUid;

    @Column(name = "orthanc_study_id", nullable = false, updatable = false)
    private String orthancStudyId;

    @Column(name = "accession_number")
    private String accessionNumber;

    @Column(name = "study_date")
    private LocalDate studyDate;

    @Column(name = "study_description")
    private String studyDescription;

    private String modalities;

    @Column(name = "number_of_series", nullable = false)
    private int numberOfSeries;

    @Column(name = "number_of_instances", nullable = false)
    private int numberOfInstances;

    @Column(name = "dicom_patient_id")
    private String dicomPatientId;

    @Column(name = "patient_id_mismatch", nullable = false)
    private boolean patientIdMismatch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source;

    @Column(name = "linked_by", updatable = false)
    private Long linkedBy;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected ImagingStudy() {
    }

    public ImagingStudy(Long patientId, String studyInstanceUid, String orthancStudyId, Source source, Long linkedBy) {
        this.patientId = patientId;
        this.studyInstanceUid = studyInstanceUid;
        this.orthancStudyId = orthancStudyId;
        this.source = source;
        this.linkedBy = linkedBy;
        this.linkedAt = Instant.now();
    }

    /** Refreshes the cached DICOM metadata from Orthanc. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public void updateMetadata(String accession, LocalDate date, String description, String modalities,
                               int series, int instances, String dicomPatientId, boolean mismatch) {
        this.accessionNumber = accession;
        this.studyDate = date;
        this.studyDescription = description;
        this.modalities = modalities;
        this.numberOfSeries = series;
        this.numberOfInstances = instances;
        this.dicomPatientId = dicomPatientId;
        this.patientIdMismatch = mismatch;
    }

    public void link(Long encounterId, Long orderId) {
        if (encounterId != null) {
            this.encounterId = encounterId;
        }
        if (orderId != null) {
            this.orderId = orderId;
        }
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Long getEncounterId() { return encounterId; }
    public Long getOrderId() { return orderId; }
    public String getStudyInstanceUid() { return studyInstanceUid; }
    public String getOrthancStudyId() { return orthancStudyId; }
    public String getAccessionNumber() { return accessionNumber; }
    public LocalDate getStudyDate() { return studyDate; }
    public String getStudyDescription() { return studyDescription; }
    public String getModalities() { return modalities; }
    public int getNumberOfSeries() { return numberOfSeries; }
    public int getNumberOfInstances() { return numberOfInstances; }
    public String getDicomPatientId() { return dicomPatientId; }
    public boolean isPatientIdMismatch() { return patientIdMismatch; }
    public Source getSource() { return source; }
    public Long getLinkedBy() { return linkedBy; }
    public Instant getLinkedAt() { return linkedAt; }
}
