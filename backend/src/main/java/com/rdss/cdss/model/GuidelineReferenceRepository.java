package com.rdss.cdss.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuidelineReferenceRepository extends JpaRepository<GuidelineReference, Long> {

    List<GuidelineReference> findAllByOrderByCodeAsc();
}
