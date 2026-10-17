package com.rdss.diagnoses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DiagnosisDtos {

    private DiagnosisDtos() {
    }

    public static final String CODE_REGEX = "^[A-Z][0-9][0-9A-Z](\\.[0-9A-Z]{1,4})?$";

    public record Icd10Response(String code, String description, boolean billable, String codeSystem,
                                String codeVersion, String source, boolean active) {
        static Icd10Response from(Icd10Code c) {
            return new Icd10Response(c.getCode(), c.getDescription(), c.isBillable(), c.getCodeSystem(),
                    c.getCodeVersion(), c.getSource(), c.isActive());
        }
    }

    public record ImportResult(String codeVersion, int imported, int billable, int rejectedLines,
                               List<String> rejectedSamples) {
    }

    public record DiagnosisRequest(Long encounterId,
                                   @NotBlank @Pattern(regexp = CODE_REGEX, message = "must be an ICD-10-CM code such as J44.1")
                                   String code,
                                   @NotNull Diagnosis.Type type,
                                   @PastOrPresent LocalDate onsetDate,
                                   @Size(max = 2000) String notes) {
    }

    public record DiagnosisResponse(Long id, Long patientId, Long encounterId, String code, String codeVersion,
                                    String description, Diagnosis.Type type, Diagnosis.Status status,
                                    LocalDate onsetDate, LocalDate resolvedDate, String notes, String errorReason,
                                    Long recordedBy, String recordedByName, Instant recordedAt) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
