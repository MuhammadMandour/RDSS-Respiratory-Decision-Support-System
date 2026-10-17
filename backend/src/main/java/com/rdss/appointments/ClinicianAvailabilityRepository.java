package com.rdss.appointments;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicianAvailabilityRepository extends JpaRepository<ClinicianAvailability, Long> {

    List<ClinicianAvailability> findByClinicianIdAndActiveTrueOrderByDayOfWeekAscStartTimeAsc(Long clinicianId);
}
