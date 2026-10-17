package com.rdss.encounters;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TreatmentPlanRepository extends JpaRepository<TreatmentPlan, Long> {

    List<TreatmentPlan> findByPatientIdOrderByStartDateDescIdDesc(Long patientId);
}
