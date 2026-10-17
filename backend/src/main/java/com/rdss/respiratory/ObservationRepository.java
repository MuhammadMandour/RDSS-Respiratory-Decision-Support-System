package com.rdss.respiratory;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObservationRepository extends JpaRepository<Observation, Long> {

    Page<Observation> findByPatientId(Long patientId, Pageable pageable);

    Page<Observation> findByPatientIdAndEncounterId(Long patientId, Long encounterId, Pageable pageable);

    List<Observation> findByPatientIdAndEnteredInErrorFalseAndObservedAtAfterOrderByObservedAtDesc(
            Long patientId, Instant after);

    List<Observation> findTop2ByPatientIdAndEnteredInErrorFalseOrderByObservedAtDescIdDesc(Long patientId);
}
