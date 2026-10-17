package com.rdss.encounters;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicalEncounterRepository extends JpaRepository<ClinicalEncounter, Long> {

    Page<ClinicalEncounter> findByPatientId(Long patientId, Pageable pageable);

    Page<ClinicalEncounter> findByPatientIdAndStatusIn(Long patientId, Collection<ClinicalEncounter.Status> statuses,
                                                       Pageable pageable);

    boolean existsByAmendsEncounterId(Long encounterId);

    Optional<ClinicalEncounter> findByAmendsEncounterId(Long encounterId);

    Page<ClinicalEncounter> findByClinicianIdAndStatus(Long clinicianId, ClinicalEncounter.Status status,
                                                       Pageable pageable);
}
