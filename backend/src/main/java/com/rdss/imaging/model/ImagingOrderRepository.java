package com.rdss.imaging.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ImagingOrderRepository extends JpaRepository<ImagingOrder, Long> {

    List<ImagingOrder> findByPatientIdOrderByOrderedAtDesc(Long patientId);

    Optional<ImagingOrder> findByAccessionNumber(String accessionNumber);

    Optional<ImagingOrder> findByExternalOrderId(String externalOrderId);

    @Query(value = "select nextval('imaging_accession_seq')", nativeQuery = true)
    long nextAccessionNumber();
}
