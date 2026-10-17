package com.rdss.hl7;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientIdentifierRepository extends JpaRepository<PatientIdentifier, Long> {

    Optional<PatientIdentifier> findBySystemAndValue(String system, String value);

    List<PatientIdentifier> findByPatientId(Long patientId);
}
