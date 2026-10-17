package com.rdss.imaging.orthanc;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.IntegrationException;
import com.rdss.config.RdssProperties;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Orthanc REST adapter. This is the only component that holds Orthanc credentials; the
 * browser never talks to Orthanc directly.
 */
@Component
public class OrthancClient implements ImagingArchive {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() { };

    private final RestClient restClient;

    public OrthancClient(RestClient.Builder builder, RdssProperties props) {
        RdssProperties.Orthanc cfg = props.orthanc();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(cfg.connectTimeout());
        factory.setReadTimeout(cfg.readTimeout());
        var b = builder.clone().baseUrl(cfg.url()).requestFactory(factory);
        if (cfg.username() != null && !cfg.username().isBlank()) {
            b = b.defaultHeaders(h -> h.setBasicAuth(cfg.username(), cfg.password() == null ? "" : cfg.password()));
        }
        this.restClient = b.build();
    }

    /** Returns Orthanc's {@code /system} document (name, version, DICOM AET, plugin flags). */
    @Override
    public Map<String, Object> systemInfo() {
        try {
            return restClient.get().uri("/system").retrieve().body(MAP);
        } catch (RestClientException ex) {
            throw new IntegrationException("Imaging server is unreachable", ex);
        }
    }

    @Override
    public StoredInstance storeInstance(byte[] dicomFile) {
        try {
            Map<String, Object> r = restClient.post().uri("/instances")
                    .contentType(MediaType.parseMediaType("application/dicom"))
                    .body(dicomFile)
                    .retrieve().body(MAP);
            if (r == null || r.get("ID") == null) {
                throw new IntegrationException("Unexpected response from imaging server");
            }
            return new StoredInstance(str(r.get("ID")), str(r.get("ParentStudy")), "AlreadyStored".equals(r.get("Status")));
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new BusinessRuleException("File is not a valid DICOM instance");
            }
            throw new IntegrationException("Imaging server rejected the file", ex);
        } catch (RestClientException ex) {
            throw new IntegrationException("Imaging server is unreachable", ex);
        }
    }

    @Override
    public void deleteInstance(String instanceId) {
        try {
            restClient.delete().uri("/instances/{id}", instanceId).retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            throw new IntegrationException("Could not remove instance from imaging server", ex);
        }
    }

    @Override
    public Optional<String> findStudyByInstanceUid(String studyInstanceUid) {
        try {
            List<Map<String, Object>> hits = restClient.post().uri("/tools/lookup")
                    .contentType(MediaType.TEXT_PLAIN).body(studyInstanceUid).retrieve().body(LIST);
            if (hits == null) {
                return Optional.empty();
            }
            return hits.stream().filter(h -> "Study".equals(h.get("Type"))).map(h -> str(h.get("ID"))).findFirst();
        } catch (RestClientException ex) {
            throw new IntegrationException("Imaging server is unreachable", ex);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public StudyMetadata studyMetadata(String orthancStudyId) {
        try {
            Map<String, Object> study = restClient.get().uri("/studies/{id}", orthancStudyId).retrieve().body(MAP);
            List<Map<String, Object>> series = restClient.get().uri("/studies/{id}/series", orthancStudyId)
                    .retrieve().body(LIST);
            if (study == null) {
                throw new IntegrationException("Study not found on imaging server");
            }
            Map<String, Object> tags = (Map<String, Object>) study.getOrDefault("MainDicomTags", Map.of());
            Map<String, Object> patientTags = (Map<String, Object>) study.getOrDefault("PatientMainDicomTags", Map.of());
            List<SeriesMetadata> seriesList = new ArrayList<>();
            for (Map<String, Object> s : series == null ? List.<Map<String, Object>>of() : series) {
                Map<String, Object> st = (Map<String, Object>) s.getOrDefault("MainDicomTags", Map.of());
                List<?> instances = (List<?>) s.getOrDefault("Instances", List.of());
                seriesList.add(new SeriesMetadata(str(s.get("ID")), str(st.get("SeriesInstanceUID")),
                        str(st.get("Modality")), str(st.get("BodyPartExamined")), str(st.get("SeriesDescription")),
                        parseInt(st.get("SeriesNumber")), instances.size()));
            }
            return new StudyMetadata(orthancStudyId, str(tags.get("StudyInstanceUID")), str(tags.get("AccessionNumber")),
                    parseDate(tags.get("StudyDate")), str(tags.get("StudyDescription")),
                    str(patientTags.get("PatientID")), seriesList);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new IntegrationException("Study not found on imaging server");
        } catch (RestClientException ex) {
            throw new IntegrationException("Imaging server is unreachable", ex);
        }
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim();
    }

    private static Integer parseInt(Object o) {
        try {
            return o == null ? null : Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(Object o) {
        try {
            return o == null ? null : LocalDate.parse(String.valueOf(o).trim(), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
