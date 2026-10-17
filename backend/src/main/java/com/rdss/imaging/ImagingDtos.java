package com.rdss.imaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.rdss.imaging.model.ImagingFinding;
import com.rdss.imaging.model.ImagingOrder;
import com.rdss.imaging.model.ImagingStudy;

public final class ImagingDtos {

    private ImagingDtos() {
    }

    public static final String UID_REGEX = "^[0-9.]{1,64}$";

    public record OrderRequest(Long encounterId,
                               @NotBlank @Pattern(regexp = "^(CT|DX|CR|MR|US|PT|NM|OT)$") String modality,
                               @Size(max = 64) String bodyPart,
                               @NotBlank @Size(max = 255) String procedureDescription,
                               @Size(max = 1000) String clinicalIndication,
                               ImagingOrder.Priority priority) {
    }

    public record OrderResponse(Long id, Long patientId, Long encounterId, String accessionNumber, String modality,
                                String bodyPart, String procedureDescription, String clinicalIndication,
                                ImagingOrder.Priority priority, ImagingOrder.Status status, String externalOrderId,
                                Long orderedBy, String orderedByName, Instant orderedAt) {
    }

    public record LinkStudyRequest(@NotBlank @Pattern(regexp = UID_REGEX, message = "must be a DICOM UID")
                                   String studyInstanceUid, Long encounterId, Long orderId) {
    }

    public record SeriesResponse(Long id, String seriesInstanceUid, String modality, String bodyPart,
                                 String description, Integer seriesNumber, int numberOfInstances) {
    }

    public record FindingRequest(Long seriesId, @NotNull ImagingFinding.Type type,
                                 ImagingFinding.Laterality laterality, @Size(max = 255) String location,
                                 @DecimalMin("0.1") @DecimalMax("999.9") BigDecimal sizeMm,
                                 @NotBlank @Size(max = 4000) String description,
                                 @NotNull ImagingFinding.Source source, ImagingFinding.Status status,
                                 @Pattern(regexp = UID_REGEX, message = "must be a DICOM UID") String sopInstanceUid,
                                 Integer frameNumber) {
    }

    public record FindingResponse(Long id, Long studyId, Long seriesId, ImagingFinding.Type type,
                                  ImagingFinding.Laterality laterality, String location, BigDecimal sizeMm,
                                  String description, ImagingFinding.Source source, ImagingFinding.Status status,
                                  String sopInstanceUid, Integer frameNumber, String errorReason, Long recordedBy,
                                  String recordedByName, Instant recordedAt) {
    }

    public record StudySummary(Long id, Long patientId, Long encounterId, Long orderId, String studyInstanceUid,
                               String accessionNumber, LocalDate studyDate, String studyDescription, String modalities,
                               int numberOfSeries, int numberOfInstances, boolean patientIdMismatch,
                               ImagingStudy.Source source, Instant linkedAt, int findingCount) {
    }

    public record StudyResponse(StudySummary study, String dicomPatientId, List<SeriesResponse> series,
                                List<FindingResponse> findings) {
    }

    public record UploadResult(List<StudySummary> studies, int instancesStored, int instancesAlreadyPresent,
                               List<String> warnings) {
    }

    public record ViewerSessionRequest(@NotEmpty @Size(max = 4) List<Long> studyIds) {
    }

    public record ViewerSessionResponse(String viewerUrl, Instant expiresAt, List<String> studyInstanceUids,
                                        String notice) {
    }

    public record ErrorRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
