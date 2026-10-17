package com.rdss.cdss.model;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CdssAlertRepository extends JpaRepository<CdssAlert, Long> {

    List<CdssAlert> findByAssessmentIdOrderByIdAsc(Long assessmentId);

    List<CdssAlert> findByAssessmentIdIn(Collection<Long> assessmentIds);
}
