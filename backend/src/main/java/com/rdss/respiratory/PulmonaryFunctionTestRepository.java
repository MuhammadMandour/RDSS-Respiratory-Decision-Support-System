package com.rdss.respiratory;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PulmonaryFunctionTestRepository extends JpaRepository<PulmonaryFunctionTest, Long> {

    List<PulmonaryFunctionTest> findByPatientIdOrderByPerformedAtDesc(Long patientId);

    Optional<PulmonaryFunctionTest> findFirstByPatientIdAndEnteredInErrorFalseOrderByPerformedAtDesc(Long patientId);

    Optional<PulmonaryFunctionTest> findFirstByPatientIdAndEnteredInErrorFalseAndPostBronchodilatorTrueOrderByPerformedAtDesc(
            Long patientId);
}
