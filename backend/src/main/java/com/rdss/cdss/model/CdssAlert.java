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

import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.Severity;
import com.rdss.common.error.BusinessRuleException;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A finding produced by a rule during an assessment, plus the clinician's disposition. */
@Entity
@Table(name = "cdss_alert")
public class CdssAlert {

    public enum Disposition { PENDING, ACCEPTED, DISMISSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private Long assessmentId;

    @Column(name = "rule_code", nullable = false, updatable = false)
    private String ruleCode;

    @Column(name = "rule_version", nullable = false, updatable = false)
    private String ruleVersion;

    @Column(nullable = false, updatable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Severity severity;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private String explanation;

    @Column(updatable = false)
    private String suggestion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> evidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guideline_codes", nullable = false, updatable = false)
    private List<String> guidelineCodes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Disposition disposition = Disposition.PENDING;

    @Column(name = "disposition_reason")
    private String dispositionReason;

    @Column(name = "disposition_by")
    private Long dispositionBy;

    @Column(name = "disposition_at")
    private Instant dispositionAt;

    protected CdssAlert() {
    }

    public CdssAlert(Long assessmentId, String ruleVersion, Finding f, Map<String, Object> evidence) {
        this.assessmentId = assessmentId;
        this.ruleCode = f.ruleCode();
        this.ruleVersion = ruleVersion;
        this.category = f.category();
        this.severity = f.severity();
        this.title = f.title();
        this.explanation = f.explanation();
        this.suggestion = f.suggestion();
        this.evidence = evidence;
        this.guidelineCodes = f.guidelineCodes();
    }

    public void dispose(Disposition d, String reason, Long userId, Instant at) {
        if (d == Disposition.PENDING) {
            throw new BusinessRuleException("Disposition must be ACCEPTED or DISMISSED");
        }
        if (d == Disposition.DISMISSED && (reason == null || reason.isBlank())) {
            throw new BusinessRuleException("A reason is required to dismiss an alert");
        }
        disposition = d;
        dispositionReason = reason == null || reason.isBlank() ? null : reason.trim();
        dispositionBy = userId;
        dispositionAt = at;
    }

    public Long getId() { return id; }
    public Long getAssessmentId() { return assessmentId; }
    public String getRuleCode() { return ruleCode; }
    public String getRuleVersion() { return ruleVersion; }
    public String getCategory() { return category; }
    public Severity getSeverity() { return severity; }
    public String getTitle() { return title; }
    public String getExplanation() { return explanation; }
    public String getSuggestion() { return suggestion; }
    public Map<String, Object> getEvidence() { return evidence; }
    public List<String> getGuidelineCodes() { return guidelineCodes; }
    public Disposition getDisposition() { return disposition; }
    public String getDispositionReason() { return dispositionReason; }
    public Long getDispositionBy() { return dispositionBy; }
    public Instant getDispositionAt() { return dispositionAt; }
}
