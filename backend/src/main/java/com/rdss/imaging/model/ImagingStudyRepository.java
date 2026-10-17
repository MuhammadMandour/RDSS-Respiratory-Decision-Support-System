package com.rdss.imaging.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImagingStudyRepository extends JpaRepository<ImagingStudy, Long> {

    List<ImagingStudy> findByPatientIdOrderByStudyDateDescIdDesc(Long patientId);

    Optional<ImagingStudy> findByStudyInstanceUid(String studyInstanceUid);

    Optional<ImagingStudy> findByOrthancStudyId(String orthancStudyId);
}
