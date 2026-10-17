package com.rdss.encounters;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class EncounterDtos {

    private EncounterDtos() {
    }

    public record CreateEncounterRequest(@NotNull ClinicalEncounter.Type type, Instant startedAt,
                                         @Size(max = 500) String chiefComplaint) {
    }

    /** Full documentation update; {@code version} enables optimistic concurrency. */
    public record UpdateEncounterRequest(
            @NotNull Long version,
            @NotNull ClinicalEncounter.Type type,
            @Size(max = 500) String chiefComplaint,
            @Size(max = 20000) String historyOfPresentIllness,
            Boolean cough,
            @Min(0) @Max(36500) Integer coughDurationDays,
            Boolean sputum,
            @Size(max = 255) String sputumDescription,
            Boolean wheezing,
            Boolean dyspnea,
            @Min(0) @Max(4) Integer mmrcGrade,
            @Min(0) @Max(40) Integer catScore,
            Boolean chestPain,
            Boolean fever,
            Boolean hemoptysis,
            @Min(0) @Max(36500) Integer symptomDurationDays,
            @Size(max = 20000) String physicalExam,
            @Size(max = 20000) String labResults,
            @Size(max = 20000) String assessmentNotes,
            @Size(max = 20000) String planNotes,
            @Size(max = 5000) String followUp) {
    }

    public record AmendEncounterRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record EncounterSummary(Long id, Long patientId, String patientName, Long clinicianId, String clinicianName,
                                   ClinicalEncounter.Type type, ClinicalEncounter.Status status, Instant startedAt,
                                   Instant finalizedAt, String chiefComplaint, Long amendsEncounterId) {
    }

    public record EncounterResponse(
            Long id, Long patientId, Long clinicianId, String clinicianName, ClinicalEncounter.Type type,
            ClinicalEncounter.Status status, Instant startedAt, Instant finalizedAt, Long finalizedBy,
            Long amendsEncounterId, String amendmentReason, Long amendedByEncounterId,
            String chiefComplaint, String historyOfPresentIllness, Boolean cough, Integer coughDurationDays,
            Boolean sputum, String sputumDescription, Boolean wheezing, Boolean dyspnea, Integer mmrcGrade,
            Integer catScore, Boolean chestPain, Boolean fever, Boolean hemoptysis, Integer symptomDurationDays,
            String physicalExam, String labResults, String assessmentNotes, String planNotes, String followUp,
            Instant createdAt, Instant updatedAt, long version) {
    }
}
