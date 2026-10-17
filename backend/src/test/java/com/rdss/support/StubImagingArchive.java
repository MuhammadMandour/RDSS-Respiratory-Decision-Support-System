package com.rdss.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.IntegrationException;
import com.rdss.imaging.orthanc.ImagingArchive;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * In-memory stand-in for Orthanc used by integration tests. A "DICOM file" is the text
 * {@code DICM|<studyUid>|<seriesUid>|<sopUid>|<patientId>|<modality>}; anything else is rejected
 * like a non-DICOM upload. {@link #systemInfo()} reports the archive as unreachable so the
 * public status endpoint behaves as without Orthanc.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubImagingArchive {

    @Bean
    @Primary
    Stub stubImagingArchive() {
        return new Stub();
    }

    public static byte[] dicom(String studyUid, String seriesUid, String sopUid, String patientId, String modality) {
        return String.join("|", "DICM", studyUid, seriesUid, sopUid, patientId, modality).getBytes(StandardCharsets.UTF_8);
    }

    public static class Stub implements ImagingArchive {

        record Instance(String id, String studyUid, String seriesUid, String patientId, String modality) {
        }

        final Map<String, Instance> instances = new ConcurrentHashMap<>();
        public final Set<String> deleted = ConcurrentHashMap.newKeySet();

        static String studyId(String uid) {
            return "orthanc-study-" + uid;
        }

        @Override
        public Map<String, Object> systemInfo() {
            throw new IntegrationException("Imaging server is unreachable");
        }

        @Override
        public StoredInstance storeInstance(byte[] file) {
            String[] p = new String(file, StandardCharsets.UTF_8).split("\\|");
            if (p.length != 6 || !"DICM".equals(p[0])) {
                throw new BusinessRuleException("File is not a valid DICOM instance");
            }
            String id = "orthanc-instance-" + p[3];
            boolean already = instances.containsKey(id);
            instances.put(id, new Instance(id, p[1], p[2], p[4], p[5]));
            return new StoredInstance(id, studyId(p[1]), already);
        }

        @Override
        public void deleteInstance(String instanceId) {
            instances.remove(instanceId);
            deleted.add(instanceId);
        }

        @Override
        public Optional<String> findStudyByInstanceUid(String uid) {
            return instances.values().stream().filter(i -> i.studyUid().equals(uid)).findFirst()
                    .map(i -> studyId(i.studyUid()));
        }

        @Override
        public StudyMetadata studyMetadata(String orthancStudyId) {
            List<Instance> in = instances.values().stream().filter(i -> studyId(i.studyUid()).equals(orthancStudyId)).toList();
            if (in.isEmpty()) {
                throw new IntegrationException("Study not found on imaging server");
            }
            List<SeriesMetadata> series = new ArrayList<>();
            in.stream().map(Instance::seriesUid).distinct().forEach(s -> {
                List<Instance> si = in.stream().filter(i -> i.seriesUid().equals(s)).toList();
                series.add(new SeriesMetadata("orthanc-series-" + s, s, si.get(0).modality(), "CHEST", "Synthetic",
                        series.size() + 1, si.size()));
            });
            return new StudyMetadata(orthancStudyId, in.get(0).studyUid(), "ACC" + in.get(0).studyUid().hashCode(),
                    LocalDate.of(2030, 1, 15), "SYNTHETIC TEST STUDY", in.get(0).patientId(), series);
        }
    }
}
