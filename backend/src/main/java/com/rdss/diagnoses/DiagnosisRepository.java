package com.rdss.diagnoses;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisRepository extends JpaRepository<Diagnosis, Long> {

    List<Diagnosis> findByPatientIdOrderByRecordedAtDesc(Long patientId);
}
