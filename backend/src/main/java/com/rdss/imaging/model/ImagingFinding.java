package com.rdss.imaging.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.rdss.common.error.ConflictException;

/** A documented finding on a study. Findings are never deleted; corrections use ENTERED_IN_ERROR. */
@Entity
@Table(name = "imaging_finding")
public class ImagingFinding {

    public enum Type {
        PULMONARY_OPACITY, CONSOLIDATION, PLEURAL_EFFUSION, PULMONARY_NODULE, PNEUMOTHORAX, ATELECTASIS,
        INTERSTITIAL_CHANGES, EMPHYSEMA, BRONCHIECTASIS, CARDIOMEGALY, OTHER
    }

    public enum Laterality { LEFT, RIGHT, BILATERAL, NOT_APPLICABLE }

    public enum Source { CLINICIAN, RADIOLOGIST_REPORT, DEMONSTRATION, AI_UNVALIDATED }

    public enum Status { PRELIMINARY, FINAL, ENTERED_IN_ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "study_id", nullable = false, updatable = false)
    private Long studyId;

    @Column(name = "series_id", updatable = false)
    private Long seriesId;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false, updatable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Laterality laterality;

    @Column(updatable = false)
    private String location;

    @Column(name = "size_mm", updatable = false)
    private BigDecimal sizeMm;

    @Column(nullable = false, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "sop_instance_uid", updatable = false)
    private String sopInstanceUid;

    @Column(name = "frame_number", updatable = false)
    private Integer frameNumber;

    @Column(name = "error_reason")
    private String errorReason;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected ImagingFinding() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ImagingFinding(Long studyId, Long seriesId, Type type, Laterality laterality, String location,
                          BigDecimal sizeMm, String description, Source source, Status status, String sopInstanceUid,
                          Integer frameNumber, Long recordedBy) {
        this.studyId = studyId;
        this.seriesId = seriesId;
        this.type = type;
        this.laterality = laterality;
        this.location = location;
        this.sizeMm = sizeMm;
        this.description = description;
        this.source = source;
        this.status = status;
        this.sopInstanceUid = sopInstanceUid;
        this.frameNumber = frameNumber;
        this.recordedBy = recordedBy;
        this.recordedAt = Instant.now();
    }

    public void finalizeFinding() {
        if (status != Status.PRELIMINARY) {
            throw new ConflictException("Only preliminary findings can be finalized");
        }
        status = Status.FINAL;
    }

    public void markEnteredInError(String reason) {
        if (status == Status.ENTERED_IN_ERROR) {
            throw new ConflictException("Finding " + id + " is already marked as entered in error");
        }
        status = Status.ENTERED_IN_ERROR;
        errorReason = reason;
    }

    public Long getId() { return id; }
    public Long getStudyId() { return studyId; }
    public Long getSeriesId() { return seriesId; }
    public Type getType() { return type; }
    public Laterality getLaterality() { return laterality; }
    public String getLocation() { return location; }
    public BigDecimal getSizeMm() { return sizeMm; }
    public String getDescription() { return description; }
    public Source getSource() { return source; }
    public Status getStatus() { return status; }
    public String getSopInstanceUid() { return sopInstanceUid; }
    public Integer getFrameNumber() { return frameNumber; }
    public String getErrorReason() { return errorReason; }
    public Long getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
