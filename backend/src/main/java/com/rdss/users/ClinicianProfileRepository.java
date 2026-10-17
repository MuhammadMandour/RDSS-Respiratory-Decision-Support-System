package com.rdss.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClinicianProfileRepository extends JpaRepository<ClinicianProfile, Long> {

    @EntityGraph(attributePaths = {"user", "department"})
    Optional<ClinicianProfile> findByUserId(Long userId);

    @EntityGraph(attributePaths = {"user", "department"})
    @Query("select c from ClinicianProfile c where c.user.enabled = true order by c.user.fullName")
    List<ClinicianProfile> findAllActive();
}
