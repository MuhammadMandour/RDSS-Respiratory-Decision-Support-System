package com.rdss.respiratory;

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

/** A set of vital signs taken at one time. Append-only; mistakes are flagged, not edited. */
@Entity
@Table(name = "observation")
public class Observation {

    /** ACVPU consciousness scale (NEWS2). */
    public enum Consciousness { ALERT, CONFUSION, VOICE, PAIN, UNRESPONSIVE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "encounter_id", updatable = false)
    private Long encounterId;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    @Column(name = "respiratory_rate", updatable = false)
    private Integer respiratoryRate;

    @Column(name = "heart_rate", updatable = false)
    private Integer heartRate;

    @Column(name = "systolic_bp", updatable = false)
    private Integer systolicBp;

    @Column(name = "diastolic_bp", updatable = false)
    private Integer diastolicBp;

    @Column(name = "temperature_c", updatable = false)
    private BigDecimal temperatureC;

    @Column(updatable = false)
    private Integer spo2;

    @Column(name = "on_supplemental_oxygen", updatable = false)
    private Boolean onSupplementalOxygen;

    @Column(name = "oxygen_flow_lpm", updatable = false)
    private BigDecimal oxygenFlowLpm;

    @Column(name = "fio2_percent", updatable = false)
    private Integer fio2Percent;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private Consciousness consciousness;

    @Column(name = "peak_flow_lpm", updatable = false)
    private Integer peakFlowLpm;

    @Column(updatable = false)
    private String notes;

    @Column(name = "entered_in_error", nullable = false)
    private boolean enteredInError;

    @Column(name = "error_reason")
    private String errorReason;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Observation() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public Observation(Long patientId, Long encounterId, Instant observedAt, Integer respiratoryRate,
                       Integer heartRate, Integer systolicBp, Integer diastolicBp, BigDecimal temperatureC,
                       Integer spo2, Boolean onSupplementalOxygen, BigDecimal oxygenFlowLpm, Integer fio2Percent,
                       Consciousness consciousness, Integer peakFlowLpm, String notes, Long recordedBy) {
        this.patientId = patientId;
        this.encounterId = encounterId;
        this.observedAt = observedAt;
        this.respiratoryRate = respiratoryRate;
        this.heartRate = heartRate;
        this.systolicBp = systolicBp;
        this.diastolicBp = diastolicBp;
        this.temperatureC = temperatureC;
        this.spo2 = spo2;
        this.onSupplementalOxygen = onSupplementalOxygen;
        this.oxygenFlowLpm = oxygenFlowLpm;
        this.fio2Percent = fio2Percent;
        this.consciousness = consciousness;
        this.peakFlowLpm = peakFlowLpm;
        this.notes = notes;
        this.recordedBy = recordedBy;
        this.createdAt = Instant.now();
    }

    public void markEnteredInError(String reason) {
        if (enteredInError) {
            throw new ConflictException("Observation " + id + " is already marked as entered in error");
        }
        enteredInError = true;
        errorReason = reason;
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Long getEncounterId() { return encounterId; }
    public Instant getObservedAt() { return observedAt; }
    public Integer getRespiratoryRate() { return respiratoryRate; }
    public Integer getHeartRate() { return heartRate; }
    public Integer getSystolicBp() { return systolicBp; }
    public Integer getDiastolicBp() { return diastolicBp; }
    public BigDecimal getTemperatureC() { return temperatureC; }
    public Integer getSpo2() { return spo2; }
    public Boolean getOnSupplementalOxygen() { return onSupplementalOxygen; }
    public BigDecimal getOxygenFlowLpm() { return oxygenFlowLpm; }
    public Integer getFio2Percent() { return fio2Percent; }
    public Consciousness getConsciousness() { return consciousness; }
    public Integer getPeakFlowLpm() { return peakFlowLpm; }
    public String getNotes() { return notes; }
    public boolean isEnteredInError() { return enteredInError; }
    public String getErrorReason() { return errorReason; }
    public Long getRecordedBy() { return recordedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
