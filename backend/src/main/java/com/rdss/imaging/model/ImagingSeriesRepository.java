package com.rdss.imaging.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImagingSeriesRepository extends JpaRepository<ImagingSeries, Long> {

    List<ImagingSeries> findByStudyIdOrderBySeriesNumberAsc(Long studyId);

    Optional<ImagingSeries> findBySeriesInstanceUid(String seriesInstanceUid);
}
