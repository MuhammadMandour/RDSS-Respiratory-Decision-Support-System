package com.rdss.respiratory;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RespiratoryHistoryRepository extends JpaRepository<RespiratoryHistory, Long> {

    Optional<RespiratoryHistory> findFirstByPatientIdOrderByVersionNoDesc(Long patientId);

    List<RespiratoryHistory> findByPatientIdOrderByVersionNoDesc(Long patientId);
}
