package com.rdss.imaging.model;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImagingFindingRepository extends JpaRepository<ImagingFinding, Long> {

    List<ImagingFinding> findByStudyIdOrderByRecordedAtDesc(Long studyId);

    /** Valid findings on a patient's studies recorded since a cut-off, newest first. */
    @Query("""
            select f from ImagingFinding f, ImagingStudy s
            where f.studyId = s.id and s.patientId = :patientId
              and f.status <> com.rdss.imaging.model.ImagingFinding.Status.ENTERED_IN_ERROR
              and f.recordedAt >= :since
            order by f.recordedAt desc
            """)
    List<ImagingFinding> findCurrentForPatient(@Param("patientId") Long patientId, @Param("since") Instant since);
}
