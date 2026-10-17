package com.rdss.cdss.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CdssRuleDefinitionRepository extends JpaRepository<CdssRuleDefinition, Long> {

    Optional<CdssRuleDefinition> findByCode(String code);

    List<CdssRuleDefinition> findAllByOrderByCategoryAscCodeAsc();
}
