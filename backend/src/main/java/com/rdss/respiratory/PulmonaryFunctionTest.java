package com.rdss.respiratory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.rdss.common.error.ConflictException;

/** Spirometry / lung function result. Append-only; mistakes are flagged, not edited. */
@Entity
@Table(name = "pulmonary_function_test")
public class PulmonaryFunctionTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "encounter_id", updatable = false)
    private Long encounterId;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

    @Column(name = "post_bronchodilator", nullable = false, updatable = false)
    private boolean postBronchodilator;

    @Column(name = "fev1_l", updatable = false)
    private BigDecimal fev1L;

    @Column(name = "fvc_l", updatable = false)
    private BigDecimal fvcL;

    @Column(name = "fev1_percent_predicted", updatable = false)
    private Integer fev1PercentPredicted;

    @Column(name = "fvc_percent_predicted", updatable = false)
    private Integer fvcPercentPredicted;

    @Column(name = "dlco_percent_predicted", updatable = false)
    private Integer dlcoPercentPredicted;

    @Column(name = "bronchodilator_response_percent", updatable = false)
    private BigDecimal bronchodilatorResponsePercent;

    @Column(name = "bronchodilator_response_ml", updatable = false)
    private Integer bronchodilatorResponseMl;

    @Column(name = "quality_grade", updatable = false)
    private String qualityGrade;

    @Column(updatable = false)
    private String interpretation;

    @Column(name = "entered_in_error", nullable = false)
    private boolean enteredInError;

    @Column(name = "error_reason")
    private String errorReason;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PulmonaryFunctionTest() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public PulmonaryFunctionTest(Long patientId, Long encounterId, Instant performedAt, boolean postBronchodilator,
                                 BigDecimal fev1L, BigDecimal fvcL, Integer fev1PercentPredicted,
                                 Integer fvcPercentPredicted, Integer dlcoPercentPredicted,
                                 BigDecimal bronchodilatorResponsePercent, Integer bronchodilatorResponseMl,
                                 String qualityGrade, String interpretation, Long recordedBy) {
        this.patientId = patientId;
        this.encounterId = encounterId;
        this.performedAt = performedAt;
        this.postBronchodilator = postBronchodilator;
        this.fev1L = fev1L;
        this.fvcL = fvcL;
        this.fev1PercentPredicted = fev1PercentPredicted;
        this.fvcPercentPredicted = fvcPercentPredicted;
        this.dlcoPercentPredicted = dlcoPercentPredicted;
        this.bronchodilatorResponsePercent = bronchodilatorResponsePercent;
        this.bronchodilatorResponseMl = bronchodilatorResponseMl;
        this.qualityGrade = qualityGrade;
        this.interpretation = interpretation;
        this.recordedBy = recordedBy;
        this.createdAt = Instant.now();
    }

    /** FEV1/FVC as a fraction (e.g. 0.65), or null if either value is missing. */
    public BigDecimal fev1FvcRatio() {
        if (fev1L == null || fvcL == null || fvcL.signum() == 0) {
            return null;
        }
        return fev1L.divide(fvcL, 3, RoundingMode.HALF_UP);
    }

    public void markEnteredInError(String reason) {
        if (enteredInError) {
            throw new ConflictException("Pulmonary function test " + id + " is already marked as entered in error");
        }
        enteredInError = true;
        errorReason = reason;
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Long getEncounterId() { return encounterId; }
    public Instant getPerformedAt() { return performedAt; }
    public boolean isPostBronchodilator() { return postBronchodilator; }
    public BigDecimal getFev1L() { return fev1L; }
    public BigDecimal getFvcL() { return fvcL; }
    public Integer getFev1PercentPredicted() { return fev1PercentPredicted; }
    public Integer getFvcPercentPredicted() { return fvcPercentPredicted; }
    public Integer getDlcoPercentPredicted() { return dlcoPercentPredicted; }
    public BigDecimal getBronchodilatorResponsePercent() { return bronchodilatorResponsePercent; }
    public Integer getBronchodilatorResponseMl() { return bronchodilatorResponseMl; }
    public String getQualityGrade() { return qualityGrade; }
    public String getInterpretation() { return interpretation; }
    public boolean isEnteredInError() { return enteredInError; }
    public String getErrorReason() { return errorReason; }
    public Long getRecordedBy() { return recordedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
