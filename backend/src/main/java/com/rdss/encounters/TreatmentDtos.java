package com.rdss.encounters;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TreatmentDtos {

    private TreatmentDtos() {
    }

    public record TreatmentPlanRequest(Long encounterId,
                                       @NotBlank @Size(max = 200) String title,
                                       @Size(max = 10000) String goals,
                                       @Size(max = 10000) String interventions,
                                       @Size(max = 5000) String followUpInstructions,
                                       @NotNull LocalDate startDate,
                                       LocalDate reviewDate,
                                       TreatmentPlan.Status status,
                                       /** Required on update for optimistic concurrency. */
                                       Long version) {
    }

    public record TreatmentPlanResponse(Long id, Long patientId, Long encounterId, String title, String goals,
                                        String interventions, String followUpInstructions, LocalDate startDate,
                                        LocalDate reviewDate, TreatmentPlan.Status status, Long createdBy,
                                        String createdByName, Instant createdAt, Instant updatedAt, long version) {
    }

    public record PrescriptionRequest(Long encounterId,
                                      @NotBlank @Size(max = 200) String medicationName,
                                      @NotBlank @Size(max = 100) String dose,
                                      @NotBlank @Size(max = 50) String route,
                                      @NotBlank @Size(max = 100) String frequency,
                                      @Size(max = 100) String duration,
                                      @Size(max = 50) String quantity,
                                      @Min(0) @Max(12) int refills,
                                      @Size(max = 1000) String instructions) {
    }

    public record DiscontinueRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record PrescriptionResponse(Long id, Long patientId, Long encounterId, String medicationName, String dose,
                                       String route, String frequency, String duration, String quantity, int refills,
                                       String instructions, Prescription.Status status, String discontinuedReason,
                                       Long prescribedBy, String prescribedByName, Instant prescribedAt) {
    }
}
