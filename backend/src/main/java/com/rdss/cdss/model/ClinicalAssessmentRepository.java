package com.rdss.cdss.model;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicalAssessmentRepository extends JpaRepository<ClinicalAssessment, Long> {

    Page<ClinicalAssessment> findByPatientId(Long patientId, Pageable pageable);

    Page<ClinicalAssessment> findByPatientIdAndEncounterId(Long patientId, Long encounterId, Pageable pageable);
}
