package com.rdss.imaging.orthanc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port to the DICOM archive. The production adapter is {@link OrthancClient}; tests use a stub.
 * Pixel data never passes through PostgreSQL.
 */
public interface ImagingArchive {

    record StoredInstance(String instanceId, String studyId, boolean alreadyStored) {
    }

    record SeriesMetadata(String orthancSeriesId, String seriesInstanceUid, String modality, String bodyPart,
                          String description, Integer seriesNumber, int instances) {
    }

    record StudyMetadata(String orthancStudyId, String studyInstanceUid, String accessionNumber, LocalDate studyDate,
                         String description, String dicomPatientId, List<SeriesMetadata> series) {
    }

    Map<String, Object> systemInfo();

    /** Stores one DICOM Part-10 file. Throws {@code BusinessRuleException} if it is not valid DICOM. */
    StoredInstance storeInstance(byte[] dicomFile);

    void deleteInstance(String instanceId);

    Optional<String> findStudyByInstanceUid(String studyInstanceUid);

    StudyMetadata studyMetadata(String orthancStudyId);
}
