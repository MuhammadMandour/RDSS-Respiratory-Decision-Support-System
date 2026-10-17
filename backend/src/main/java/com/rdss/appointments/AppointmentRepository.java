package com.rdss.appointments;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    java.util.Optional<Appointment> findByExternalId(String externalId);

    @Query("""
            select count(a) > 0 from Appointment a
            where a.clinicianId = :clinicianId and a.status in :statuses
              and a.startsAt < :endsAt and a.endsAt > :startsAt
            """)
    boolean clinicianHasOverlap(@Param("clinicianId") Long clinicianId, @Param("startsAt") Instant startsAt,
                                @Param("endsAt") Instant endsAt,
                                @Param("statuses") Collection<Appointment.Status> statuses);

    @Query("""
            select count(a) > 0 from Appointment a
            where a.patientId = :patientId and a.status in :statuses
              and a.startsAt < :endsAt and a.endsAt > :startsAt
            """)
    boolean patientHasOverlap(@Param("patientId") Long patientId, @Param("startsAt") Instant startsAt,
                              @Param("endsAt") Instant endsAt,
                              @Param("statuses") Collection<Appointment.Status> statuses);

    @Query("""
            select a from Appointment a
            where a.clinicianId = :clinicianId and a.status in :statuses
              and a.startsAt < :to and a.endsAt > :from
            """)
    List<Appointment> findBlocking(@Param("clinicianId") Long clinicianId, @Param("from") Instant from,
                                   @Param("to") Instant to,
                                   @Param("statuses") Collection<Appointment.Status> statuses);

    @Query("""
            select a from Appointment a
            where (:patientId is null or a.patientId = :patientId)
              and (:clinicianId is null or a.clinicianId = :clinicianId)
              and (:status is null or a.status = :status)
              and (cast(:from as timestamp) is null or a.startsAt >= :from)
              and (cast(:to as timestamp) is null or a.startsAt < :to)
            """)
    Page<Appointment> search(@Param("patientId") Long patientId, @Param("clinicianId") Long clinicianId,
                             @Param("status") Appointment.Status status, @Param("from") Instant from,
                             @Param("to") Instant to, Pageable pageable);

    /** Clinician search over own appointments plus any appointments of care-team patients. */
    @Query("""
            select a from Appointment a
            where (a.clinicianId = :me or exists (select 1 from PatientCareTeamMember m
                       where m.id.patientId = a.patientId and m.id.clinicianUserId = :me))
              and (:patientId is null or a.patientId = :patientId)
              and (:clinicianId is null or a.clinicianId = :clinicianId)
              and (:status is null or a.status = :status)
              and (cast(:from as timestamp) is null or a.startsAt >= :from)
              and (cast(:to as timestamp) is null or a.startsAt < :to)
            """)
    Page<Appointment> searchForClinician(@Param("me") Long me, @Param("patientId") Long patientId,
                                         @Param("clinicianId") Long clinicianId,
                                         @Param("status") Appointment.Status status, @Param("from") Instant from,
                                         @Param("to") Instant to, Pageable pageable);
}
