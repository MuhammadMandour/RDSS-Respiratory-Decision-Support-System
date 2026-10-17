package com.rdss.patients;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findByUserId(Long userId);

    Optional<Patient> findByMrn(String mrn);

    @Query(value = "select nextval('patient_mrn_seq')", nativeQuery = true)
    long nextMrnNumber();

    /** Directory search over all patients (administrators). */
    @Query("""
            select p from Patient p
            where (:q is null or lower(p.lastName) like :q or lower(p.firstName) like :q or lower(p.mrn) like :q)
              and (:includeInactive = true or p.active = true)
            """)
    Page<Patient> searchAll(@Param("q") String likePattern, @Param("includeInactive") boolean includeInactive,
                            Pageable pageable);

    /** Directory search restricted to a clinician's care team. */
    @Query("""
            select p from Patient p
            where exists (select 1 from PatientCareTeamMember m where m.id.patientId = p.id and m.id.clinicianUserId = :clinicianId)
              and (:q is null or lower(p.lastName) like :q or lower(p.firstName) like :q or lower(p.mrn) like :q)
              and (:includeInactive = true or p.active = true)
            """)
    Page<Patient> searchForClinician(@Param("clinicianId") Long clinicianId, @Param("q") String likePattern,
                                     @Param("includeInactive") boolean includeInactive, Pageable pageable);
}
