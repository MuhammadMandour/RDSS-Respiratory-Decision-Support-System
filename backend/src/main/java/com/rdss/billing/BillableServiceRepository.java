package com.rdss.billing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BillableServiceRepository extends JpaRepository<BillableService, Long> {

    List<BillableService> findAllByOrderByCategoryAscNameAsc();

    Optional<BillableService> findByCode(String code);
}
