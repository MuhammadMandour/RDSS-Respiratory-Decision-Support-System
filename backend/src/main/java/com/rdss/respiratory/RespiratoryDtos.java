package com.rdss.respiratory;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

public final class RespiratoryDtos {

    private RespiratoryDtos() {
    }

    public record RespiratoryHistoryRequest(
            boolean asthma, boolean copd, boolean bronchiectasis, boolean interstitialLungDisease,
            boolean obstructiveSleepApnea, boolean previousTuberculosis,
            boolean hypercapnicRespiratoryFailureRisk,
            @Min(70) @Max(100) Integer targetSpo2Low,
            @Min(70) @Max(100) Integer targetSpo2High,
            boolean homeOxygen,
            @Min(0) @Max(100) Integer exacerbationsLast12m,
            @Min(0) @Max(100) Integer hospitalizationsLast12m,
            boolean previousIntubation,
            @Size(max = 2000) String inhalerTherapy,
            @Size(max = 2000) String occupationalExposures,
            @Size(max = 2000) String familyHistory,
            @Size(max = 2000) String vaccinations,
            @Size(max = 2000) String comorbidities,
            @Size(max = 5000) String notes) {

        @AssertTrue(message = "target SpO2 low must be below target SpO2 high")
        public boolean isTargetRangeValid() {
            return targetSpo2Low == null || targetSpo2High == null || targetSpo2Low < targetSpo2High;
        }
    }

    public record RespiratoryHistoryResponse(
            Long id, Long patientId, int versionNo, Long supersedesId, boolean asthma, boolean copd,
            boolean bronchiectasis, boolean interstitialLungDisease, boolean obstructiveSleepApnea,
            boolean previousTuberculosis, boolean hypercapnicRespiratoryFailureRisk, Integer targetSpo2Low,
            Integer targetSpo2High, boolean homeOxygen, Integer exacerbationsLast12m, Integer hospitalizationsLast12m,
            boolean previousIntubation, String inhalerTherapy, String occupationalExposures, String familyHistory,
            String vaccinations, String comorbidities, String notes, Long recordedBy, String recordedByName,
            Instant recordedAt) {
    }

    public record ObservationRequest(
            Long encounterId,
            @NotNull @PastOrPresent Instant observedAt,
            @Min(0) @Max(100) Integer respiratoryRate,
            @Min(0) @Max(300) Integer heartRate,
            @Min(30) @Max(300) Integer systolicBp,
            @Min(10) @Max(200) Integer diastolicBp,
            @DecimalMin("25.0") @DecimalMax("45.0") BigDecimal temperatureC,
            @Min(40) @Max(100) Integer spo2,
            Boolean onSupplementalOxygen,
            @DecimalMin("0.0") @DecimalMax("80.0") BigDecimal oxygenFlowLpm,
            @Min(21) @Max(100) Integer fio2Percent,
            Observation.Consciousness consciousness,
            @Min(30) @Max(1000) Integer peakFlowLpm,
            @Size(max = 1000) String notes) {

        @AssertTrue(message = "systolic blood pressure must be greater than diastolic")
        public boolean isBloodPressureValid() {
            return systolicBp == null || diastolicBp == null || systolicBp > diastolicBp;
        }

        @AssertTrue(message = "at least one measurement is required")
        public boolean isNotEmpty() {
            return respiratoryRate != null || heartRate != null || systolicBp != null || temperatureC != null
                    || spo2 != null || consciousness != null || peakFlowLpm != null;
        }
    }

    public record ObservationResponse(
            Long id, Long patientId, Long encounterId, Instant observedAt, Integer respiratoryRate, Integer heartRate,
            Integer systolicBp, Integer diastolicBp, BigDecimal temperatureC, Integer spo2,
            Boolean onSupplementalOxygen, BigDecimal oxygenFlowLpm, Integer fio2Percent,
            Observation.Consciousness consciousness, Integer peakFlowLpm, String notes, boolean enteredInError,
            String errorReason, Long recordedBy, String recordedByName, Instant createdAt) {
    }

    public record EnteredInErrorRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record PulmonaryFunctionTestRequest(
            Long encounterId,
            @NotNull @PastOrPresent Instant performedAt,
            boolean postBronchodilator,
            @DecimalMin("0.1") @DecimalMax("9.0") BigDecimal fev1L,
            @DecimalMin("0.1") @DecimalMax("10.0") BigDecimal fvcL,
            @Min(5) @Max(200) Integer fev1PercentPredicted,
            @Min(5) @Max(200) Integer fvcPercentPredicted,
            @Min(5) @Max(200) Integer dlcoPercentPredicted,
            @DecimalMin("-100.0") @DecimalMax("300.0") BigDecimal bronchodilatorResponsePercent,
            @Min(-2000) @Max(5000) Integer bronchodilatorResponseMl,
            @Pattern(regexp = "^[A-FU]$", message = "must be one of A-F or U") String qualityGrade,
            @Size(max = 5000) String interpretation) {

        @AssertTrue(message = "FEV1 cannot exceed FVC")
        public boolean isFev1WithinFvc() {
            return fev1L == null || fvcL == null || fev1L.compareTo(fvcL) <= 0;
        }
    }

    public record PulmonaryFunctionTestResponse(
            Long id, Long patientId, Long encounterId, Instant performedAt, boolean postBronchodilator,
            BigDecimal fev1L, BigDecimal fvcL, BigDecimal fev1FvcRatio, Integer fev1PercentPredicted,
            Integer fvcPercentPredicted, Integer dlcoPercentPredicted, BigDecimal bronchodilatorResponsePercent,
            Integer bronchodilatorResponseMl, String qualityGrade, String interpretation, boolean enteredInError,
            String errorReason, Long recordedBy, String recordedByName, Instant createdAt) {
    }
}
