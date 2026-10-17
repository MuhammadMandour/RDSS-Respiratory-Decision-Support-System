package com.rdss.cdss.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Immutable snapshot of every input the rules may use. It is serialized verbatim into
 * {@code clinical_assessment.input_snapshot}, so an assessment can always be reproduced
 * and audited against exactly what the engine saw.
 */
public record RuleContext(
        Instant assessedAt,
        PatientFacts patient,
        HistoryFacts history,
        ObservationFacts latestObservation,
        ObservationFacts previousObservation,
        PftFacts latestPostBronchodilatorPft,
        PftFacts latestPft,
        EncounterFacts encounter,
        AsthmaControlAnswers asthmaControl,
        List<ImagingFindingFacts> imagingFindings) {

    /** Clinician/radiologist-documented imaging finding (never derived by RDSS from pixels). */
    public record ImagingFindingFacts(Long findingId, Long studyId, LocalDate studyDate, String modalities, String type,
                                      String laterality, BigDecimal sizeMm, String description, String source,
                                      String status, Instant recordedAt) {
    }

    public record PatientFacts(Long patientId, int ageYears, String sex, String smokingStatus, BigDecimal packYears) {
    }

    public record HistoryFacts(int versionNo, boolean asthma, boolean copd, boolean hypercapnicRespiratoryFailureRisk,
                               Integer targetSpo2Low, Integer targetSpo2High, Integer exacerbationsLast12m,
                               Integer hospitalizationsLast12m) {
    }

    public record ObservationFacts(Long id, Instant observedAt, Integer respiratoryRate, Integer spo2,
                                   Boolean onSupplementalOxygen, BigDecimal oxygenFlowLpm, Integer fio2Percent,
                                   Integer systolicBp, Integer heartRate, BigDecimal temperatureC,
                                   String consciousness) {
    }

    public record PftFacts(Long id, Instant performedAt, boolean postBronchodilator, BigDecimal fev1L,
                           BigDecimal fvcL, BigDecimal fev1FvcRatio, Integer fev1PercentPredicted,
                           BigDecimal bronchodilatorResponsePercent, Integer bronchodilatorResponseMl) {
    }

    public record EncounterFacts(Long id, String status, String chiefComplaint, Boolean cough, Boolean wheezing,
                                 Boolean dyspnea, Boolean fever, Boolean hemoptysis, Integer mmrcGrade,
                                 Integer catScore) {
    }

    /** GINA symptom-control questions for the past 4 weeks, entered by the clinician. */
    public record AsthmaControlAnswers(Boolean daytimeSymptomsMoreThanTwiceWeekly, Boolean nightWakingDueToAsthma,
                                       Boolean relieverNeededMoreThanTwiceWeekly, Boolean activityLimitation) {

        public boolean complete() {
            return daytimeSymptomsMoreThanTwiceWeekly != null && nightWakingDueToAsthma != null
                    && relieverNeededMoreThanTwiceWeekly != null && activityLimitation != null;
        }
    }
}
