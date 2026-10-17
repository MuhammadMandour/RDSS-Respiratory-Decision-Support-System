package com.rdss.patients;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientCareTeamRepository
        extends JpaRepository<PatientCareTeamMember, PatientCareTeamMember.Key> {

    @Query("select m from PatientCareTeamMember m where m.id.patientId = :patientId order by m.addedAt")
    List<PatientCareTeamMember> findByPatientId(@Param("patientId") Long patientId);
}
