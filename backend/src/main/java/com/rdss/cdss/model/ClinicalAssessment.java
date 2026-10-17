package com.rdss.cdss.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.rdss.cdss.engine.Severity;
import com.rdss.common.error.ConflictException;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single run of the decision-support engine. Inputs and outputs are immutable once written;
 * only the review fields change afterwards.
 */
@Entity
@Table(name = "clinical_assessment")
public class ClinicalAssessment {

    public enum ReviewStatus { PENDING, REVIEWED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "encounter_id", updatable = false)
    private Long encounterId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "assessed_at", nullable = false, updatable = false)
    private Instant assessedAt;

    @Column(name = "engine_version", nullable = false, updatable = false)
    private String engineVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, updatable = false)
    private Map<String, Object> inputSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_applied", nullable = false, updatable = false)
    private List<Map<String, Object>> rulesApplied;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_data", nullable = false, updatable = false)
    private List<String> missingData;

    @Column(name = "news2_score", updatable = false)
    private Integer news2Score;

    @Column(name = "news2_risk", updatable = false)
    private String news2Risk;

    @Column(name = "news2_scale", updatable = false)
    private Short news2Scale;

    @Enumerated(EnumType.STRING)
    @Column(name = "highest_severity", nullable = false, updatable = false)
    private Severity highestSeverity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inference_output", updatable = false)
    private Map<String, Object> inferenceOutput;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note")
    private String reviewNote;

    protected ClinicalAssessment() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ClinicalAssessment(Long patientId, Long encounterId, Long requestedBy, Instant assessedAt,
                              String engineVersion, Map<String, Object> inputSnapshot,
                              List<Map<String, Object>> rulesApplied, List<String> missingData, Integer news2Score,
                              String news2Risk, Short news2Scale, Severity highestSeverity,
                              Map<String, Object> inferenceOutput) {
        this.patientId = patientId;
        this.encounterId = encounterId;
        this.requestedBy = requestedBy;
        this.assessedAt = assessedAt;
        this.engineVersion = engineVersion;
        this.inputSnapshot = inputSnapshot;
        this.rulesApplied = rulesApplied;
        this.missingData = missingData;
        this.news2Score = news2Score;
        this.news2Risk = news2Risk;
        this.news2Scale = news2Scale;
        this.highestSeverity = highestSeverity;
        this.inferenceOutput = inferenceOutput;
    }

    public void review(Long userId, String note, Instant at) {
        if (reviewStatus == ReviewStatus.REVIEWED) {
            throw new ConflictException("Assessment " + id + " has already been reviewed");
        }
        reviewStatus = ReviewStatus.REVIEWED;
        reviewedBy = userId;
        reviewedAt = at;
        reviewNote = note;
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Long getEncounterId() { return encounterId; }
    public Long getRequestedBy() { return requestedBy; }
    public Instant getAssessedAt() { return assessedAt; }
    public String getEngineVersion() { return engineVersion; }
    public Map<String, Object> getInputSnapshot() { return inputSnapshot; }
    public List<Map<String, Object>> getRulesApplied() { return rulesApplied; }
    public List<String> getMissingData() { return missingData; }
    public Integer getNews2Score() { return news2Score; }
    public String getNews2Risk() { return news2Risk; }
    public Short getNews2Scale() { return news2Scale; }
    public Severity getHighestSeverity() { return highestSeverity; }
    public Map<String, Object> getInferenceOutput() { return inferenceOutput; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public Long getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewNote() { return reviewNote; }
}
